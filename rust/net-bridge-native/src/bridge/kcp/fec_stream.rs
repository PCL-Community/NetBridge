//! FECStream：可靠流之上的 RS(255,223) 块纠错中间层。
//!
//! 线上单元 = 单个 RS 码字（[`CODEWORD_LEN`] 字节），消息区
//! （[`MESSAGE_LEN`] 字节）布局为 `[len:u16 BE][payload ≤ 221B][零填充]`。
//! **整条链路上没有未保护字节**——长度字段损坏同样被 RS 纠正。
//!
//! 职责边界：
//! - 处理"穿透 UDP 校验和的静默数据损坏"：每码字 ≤16 字节错误原地纠正；
//!   超限解码失败 → `InvalidData`，此后连接进入毒化态（所有读写报错，
//!   由调用方置 FAILED 收尾）。
//! - 不处理丢包——那由下层 KCP 重传负责；本层不引入分组/等待。
//!
//! 实现为标准 `AsyncRead`/`AsyncWrite`：会话循环可经 [`tokio::io::split`]
//! 拆成读写两半（QUIC 路径同构）。写语义为缓冲式——`poll_write` 仅入累积
//! 器并尽力冲刷即计数成功；错误经毒化标记在后续操作中浮现。

use std::io;
use std::pin::Pin;
use std::task::Poll;

use fec::{RsDecoder, RsEncoder};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// RS 码字总长（GF(2^8)，CCSDS 标准 n=255）。
pub const CODEWORD_LEN: usize = 255;
/// RS 消息区长度（k=223）。
pub const MESSAGE_LEN: usize = 223;
/// 每块实际载荷上限：消息区前 2 字节为长度前缀。
pub const PAYLOAD_CAP: usize = MESSAGE_LEN - 2;

type Codeword = [u8; CODEWORD_LEN];

pub struct FecStream<Io> {
    inner: Io,
    encoder: RsEncoder,
    decoder: RsDecoder,
    // ---- 读侧状态 ----
    /// 待解码码字累积器。
    rcw: Codeword,
    rfill: usize,
    /// 最近解码出的明文与消费游标（`rplain[rpos..rlen]` 未消费）。
    rplain: [u8; MESSAGE_LEN],
    rpos: usize,
    rlen: usize,
    /// 毒化标记：RS 解码超限后置位，此后所有操作返回 InvalidData。
    poisoned: bool,
    // ---- 写侧状态 ----
    /// 消息区累积器：`wmsg[..2]` 发块时填长度，载荷自下标 2 起。
    wmsg: [u8; MESSAGE_LEN],
    wfill: usize,
    /// 待写出码字与偏移（部分写恢复点）；None 表示无在途码字。
    pending: Option<(Box<Codeword>, usize)>,
}

impl<Io> FecStream<Io> {
    pub fn new(inner: Io) -> Self {
        Self {
            inner,
            encoder: RsEncoder::new_ccsds(),
            decoder: RsDecoder::new_ccsds(),
            rcw: [0u8; CODEWORD_LEN],
            rfill: 0,
            rplain: [0u8; MESSAGE_LEN],
            rpos: 0,
            rlen: 0,
            poisoned: false,
            wmsg: [0u8; MESSAGE_LEN],
            wfill: 0,
            pending: None,
        }
    }

    /// 取出内部流。调用方须保证已 flush/shutdown 后再取。
    pub fn into_inner(self) -> Io {
        self.inner
    }

    fn poison(err_kind: io::ErrorKind, msg: &'static str) -> io::Error {
        io::Error::new(err_kind, msg)
    }
}

impl<Io: AsyncRead + Unpin> FecStream<Io> {
    /// 推进读侧直至产出明文或需要等待。返回：
    /// - `Ok(true)`：`rplain[rpos..rlen]` 有新数据；
    /// - `Ok(false)`：干净 EOF 且无任何未消费数据；
    /// - `Err`：截断 / 解码超限（毒化）。
    ///
    /// SAFETY/借用说明：inner 的 poll_read 需要 Pin，Io: Unpin 即可直接
    /// `Pin::new`。
    fn poll_decode(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<io::Result<bool>> {
        use std::task::Poll;
        if self.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        loop {
            if self.rfill == CODEWORD_LEN {
                // 解码当前完整码字。
                let result = self.decoder.decode(&self.rcw, &mut self.rplain);
                match result {
                    Ok(_corrected) => {
                        let len =
                            u16::from_be_bytes([self.rplain[0], self.rplain[1]]) as usize;
                        if len > PAYLOAD_CAP {
                            self.poisoned = true;
                            return Poll::Ready(Err(Self::poison(
                                io::ErrorKind::InvalidData,
                                "fec: corrupted length prefix",
                            )));
                        }
                        self.rpos = 2;
                        self.rlen = 2 + len;
                        self.rfill = 0;
                        return Poll::Ready(Ok(true));
                    }
                    Err(e) => {
                        self.poisoned = true;
                        return Poll::Ready(Err(io::Error::new(
                            io::ErrorKind::InvalidData,
                            format!("fec decode failed (corruption beyond capability): {e}"),
                        )));
                    }
                }
            }
            let mut buf = ReadBuf::new(&mut self.rcw[self.rfill..]);
            match Pin::new(&mut self.inner).poll_read(cx, &mut buf) {
                Poll::Ready(Ok(())) => {
                    let n = buf.filled().len();
                    if n == 0 {
                        // EOF：有半截码字为异常截断；否则干净结束。
                        if self.rfill == 0 {
                            return Poll::Ready(Ok(false));
                        }
                        self.poisoned = true;
                        return Poll::Ready(Err(Self::poison(
                            io::ErrorKind::UnexpectedEof,
                            "fec: truncated codeword",
                        )));
                    }
                    self.rfill += n;
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl<Io: AsyncRead + Unpin> AsyncRead for FecStream<Io> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        if this.rpos >= this.rlen {
            match this.poll_decode(cx) {
                Poll::Ready(Ok(true)) => {}
                // 干净 EOF：以零字节读取上报。
                Poll::Ready(Ok(false)) => return Poll::Ready(Ok(())),
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
        let avail = this.rlen - this.rpos;
        let n = avail.min(buf.remaining());
        buf.put_slice(&this.rplain[this.rpos..this.rpos + n]);
        this.rpos += n;
        Poll::Ready(Ok(()))
    }
}

impl<Io: AsyncWrite + Unpin> AsyncWrite for FecStream<Io> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if this.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        // 先冲在途码字（上次 Pending 的恢复点）。
        match Self::poll_flush_pending(this, cx)? {
            Poll::Ready(()) => {}
            Poll::Pending => return Poll::Pending,
        }
        if buf.is_empty() {
            return Poll::Ready(Err(io::Error::new(
                io::ErrorKind::WriteZero,
                "fec: empty write",
            )));
        }
        // 入累积器；满块即刻编码并尝试冲刷（失败留在 pending，后续恢复）。
        let accept = (PAYLOAD_CAP - this.wfill).min(buf.len());
        this.wmsg[2 + this.wfill..2 + this.wfill + accept]
            .copy_from_slice(&buf[..accept]);
        this.wfill += accept;
        if this.wfill == PAYLOAD_CAP {
            let cw = this.encode_accumulated();
            this.pending = Some((cw, 0));
            // 冲刷失败不回滚计数：错误经毒化/后续 flush 浮现（缓冲式语义）。
            let _ = Self::poll_flush_pending(this, cx)?;
        }
        Poll::Ready(Ok(accept))
    }

    fn poll_flush(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.poisoned {
            return Poll::Ready(Err(Self::poison(
                io::ErrorKind::InvalidData,
                "fec stream poisoned",
            )));
        }
        match Self::poll_flush_pending(this, cx)? {
            Poll::Ready(()) => {}
            Poll::Pending => return Poll::Pending,
        }
        // 尾块补齐发出。
        if this.wfill > 0 {
            let cw = this.encode_accumulated();
            this.pending = Some((cw, 0));
            match Self::poll_flush_pending(this, cx)? {
                Poll::Ready(()) => {}
                Poll::Pending => return Poll::Pending,
            }
        }
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(
        self: Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        match AsyncWrite::poll_flush(Pin::new(this), cx) {
            Poll::Ready(Ok(())) => {}
            other => return other,
        }
        Pin::new(&mut this.inner).poll_shutdown(cx)
    }
}

impl<Io: AsyncWrite + Unpin> FecStream<Io> {
    /// 将累积器编码为码字并清空累积器。
    fn encode_accumulated(&mut self) -> Box<Codeword> {
        let payload = self.wfill;
        self.wmsg[..2].copy_from_slice(&(payload as u16).to_be_bytes());
        for b in &mut self.wmsg[2 + payload..] {
            *b = 0;
        }
        let mut cw: Box<Codeword> = Box::new([0u8; CODEWORD_LEN]);
        // 分散借用：encoder 与 wmsg 为不同字段。
        let _ = self.encoder.encode(&self.wmsg, &mut cw[..]);
        self.wfill = 0;
        cw
    }

    /// 冲刷在途码字：无在途立即就绪；部分写经 offset 恢复。
    /// 关联函数形式——调用点已持有 `&mut Self`（`this`），避免与方法
    /// 调用自动重借用冲突。
    fn poll_flush_pending(
        this: &mut Self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<io::Result<()>> {
        use std::task::Poll;
        let Some((cw, offset)) = this.pending.as_mut() else {
            return Poll::Ready(Ok(()));
        };
        loop {
            match Pin::new(&mut this.inner).poll_write(cx, &cw[*offset..]) {
                Poll::Ready(Ok(n)) => {
                    *offset += n;
                    if *offset == CODEWORD_LEN {
                        this.pending = None;
                        return Poll::Ready(Ok(()));
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::pin::Pin;
    use std::task::{Context, Poll};
    use tokio::io::{duplex, AsyncReadExt, AsyncWriteExt, ReadBuf};

    /// 读侧损坏注入器：对经过的字节流按**绝对偏移**注入翻转——第 `block`
    /// 块内第 `i` 个翻转点位于 `block * 255 + (i * 37 + 11) % 255`
    /// （gcd(37,255)=1 ⇒ 块内互不重合）。仅前 `blocks` 个块注入。
    struct CorruptRead<S> {
        inner: S,
        blocks: usize,
        flips_per_block: usize,
        pos: u64,
    }

    impl<S: AsyncRead + Unpin> AsyncRead for CorruptRead<S> {
        fn poll_read(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            let this = self.get_mut();
            let cap = buf.remaining();
            if cap == 0 {
                return Poll::Ready(Ok(()));
            }
            let mut scratch = vec![0u8; cap.min(CODEWORD_LEN * 4)];
            let mut rb = ReadBuf::new(&mut scratch);
            match Pin::new(&mut this.inner).poll_read(cx, &mut rb)? {
                Poll::Ready(()) => {
                    let mut out = rb.filled().to_vec();
                    for (off, byte) in out.iter_mut().enumerate() {
                        let abs = this.pos + off as u64;
                        let block = (abs / CODEWORD_LEN as u64) as usize;
                        if block >= this.blocks {
                            continue;
                        }
                        let idx_in_block = (abs % CODEWORD_LEN as u64) as usize;
                        let flip_at = |i: usize| (i * 37 + 11) % CODEWORD_LEN;
                        if idx_in_block != 0
                            && (0..this.flips_per_block).any(|i| flip_at(i) == idx_in_block)
                        {
                            *byte ^= 0x5a;
                        }
                    }
                    this.pos += out.len() as u64;
                    buf.put_slice(&out);
                    Poll::Ready(Ok(()))
                }
                Poll::Pending => Poll::Pending,
            }
        }
    }

    #[tokio::test]
    async fn roundtrip_small_and_multi_block() {
        let (a, b) = duplex(64 * 1024);
        let (mut tx, mut rx) = (FecStream::new(a), FecStream::new(b));

        tx.write_all(b"hello fec").await.expect("send small");
        tx.write_all(&vec![0xABu8; PAYLOAD_CAP * 3 + 17]).await.expect("send multi");
        tx.flush().await.expect("flush tail");

        let mut out = vec![0u8; 9];
        rx.read_exact(&mut out).await.expect("recv small");
        assert_eq!(out, b"hello fec");

        let total = PAYLOAD_CAP * 3 + 17;
        let mut big = vec![0u8; total];
        // 精确长度读：writer 未关闭，无 EOF 可等。
        rx.read_exact(&mut big).await.expect("recv big");
        assert_eq!(big, vec![0xABu8; total]);
    }

    #[tokio::test]
    async fn corrects_intra_block_corruption() {
        // 每块 8 字节翻转（< t=16）：必须原样纠回。
        let (a, b) = duplex(64 * 1024);
        let corrupted = CorruptRead { inner: a, blocks: 4, flips_per_block: 8, pos: 0 };
        let (mut tx, mut rx) = (FecStream::new(b), FecStream::new(corrupted));

        let payload: Vec<u8> = (0..PAYLOAD_CAP * 2 + 100).map(|i| i as u8).collect();
        tx.write_all(&payload).await.expect("send");
        tx.flush().await.expect("flush");

        let mut got = vec![0u8; payload.len()];
        rx.read_exact(&mut got).await.expect("recv under corruption");
        assert_eq!(got, payload, "RS 必须把 ≤t 的损坏原样纠回");
    }

    #[tokio::test]
    async fn fails_when_corruption_exceeds_capability() {
        // 首块 40 字节翻转（>> t=16）：解码必须毒化报 InvalidData 而非吐脏数据。
        let (a, b) = duplex(64 * 1024);
        let corrupted = CorruptRead { inner: a, blocks: 1, flips_per_block: 40, pos: 0 };
        let (mut tx, mut rx) = (FecStream::new(b), FecStream::new(corrupted));

        tx.write_all(&vec![7u8; 400]).await.expect("send");
        tx.flush().await.expect("flush");

        let mut tmp = vec![0u8; 512];
        let res = rx.read(&mut tmp).await;
        assert!(
            matches!(&res, Err(e) if e.kind() == io::ErrorKind::InvalidData),
            "超限损坏必须 InvalidData，得到 {res:?}"
        );
    }

    #[tokio::test]
    async fn shutdown_flushes_tail_then_eof() {
        let (a, b) = duplex(64 * 1024);
        let mut tx = FecStream::new(a);
        let mut rx = FecStream::new(b);

        tx.write_all(b"tail").await.expect("send");
        tx.shutdown().await.expect("shutdown");

        let mut out = vec![0u8; 4];
        rx.read_exact(&mut out).await.expect("recv tail");
        assert_eq!(out, b"tail");
        // 尾块之后再次读：干净 EOF。
        assert_eq!(rx.read(&mut out).await.expect("eof"), 0);
    }
}
