//! 控制字帧层：FecStream 之上的应用帧编解码。
//!
//! 线上格式：`[type:1][len:u16 BE][payload ≤ 65535]`。type=0 数据帧
//! （MC 字节流），type=1 控制帧（现仅 `close`）。整帧位于 FEC 保护区内。
//!
//! 帧边界语义：每帧写完即 flush——不满的 FEC 尾块零填充立即发出，
//! 保证 MC 小包不滞留累积器（代价：每帧 ≥1 码字）。

use std::io;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// 数据帧：承载 MC 协议字节流。
pub const FRAME_DATA: u8 = 0;
/// 控制帧：对端收到即优雅关闭连接。
pub const FRAME_CLOSE: u8 = 1;
/// 探测帧：客户端建连即发——无握手模型下触发服务端会话创建；无数据语义，接收方忽略。
pub const FRAME_PROBE: u8 = 2;
/// 存活应答帧：服务端接纳会话后立即回发；客户端收到即置 CONNECTED（不投递 Java）。
pub const FRAME_PONG: u8 = 3;

/// 帧头长度：type(1) + len(2)。
const HEADER_LEN: usize = 3;

/// 从任意可靠字节流读出下一帧；返回 `(类型, 载荷)`。
///
/// 帧起始处干净 EOF 视作对端关闭 → `Ok((FRAME_CLOSE, 空))`（KCP 无真实
/// EOF，此为防御路径）；帧中截断以 UnexpectedEof 上抛。
pub async fn read_frame<R: AsyncRead + Unpin>(
    r: &mut R,
    payload: &mut Vec<u8>,
) -> io::Result<(u8, usize)> {
    // 首字节单独读：区分"起始干净 EOF"与帧中截断。
    let mut header = [0u8; HEADER_LEN];
    if r.read(&mut header[..1]).await? == 0 {
        return Ok((FRAME_CLOSE, 0));
    }
    r.read_exact(&mut header[1..]).await?;
    let frame_type = header[0];
    let len = u16::from_be_bytes([header[1], header[2]]) as usize;
    payload.clear();
    payload.resize(len, 0);
    if len > 0 {
        r.read_exact(payload).await?;
    }
    Ok((frame_type, len))
}

/// 写出一帧并冲刷尾块。
pub async fn write_frame<W: AsyncWrite + Unpin>(
    w: &mut W,
    frame_type: u8,
    payload: &[u8],
) -> io::Result<()> {
    let len = payload.len();
    if len > u16::MAX as usize {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("frame payload {len} exceeds u16"),
        ));
    }
    w.write_all(&[frame_type]).await?;
    w.write_all(&(len as u16).to_be_bytes()).await?;
    if !payload.is_empty() {
        w.write_all(payload).await?;
    }
    w.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::fec_stream::FecStream;
    use tokio::io::duplex;

    #[tokio::test]
    async fn frame_roundtrip() {
        let (a, b) = duplex(64 * 1024);
        let (mut tx, mut rx) = (FecStream::new(a), FecStream::new(b));

        write_frame(&mut tx, FRAME_DATA, b"mc bytes").await.expect("write data");
        write_frame(&mut tx, FRAME_DATA, &[]).await.expect("write empty");
        write_frame(&mut tx, FRAME_CLOSE, b"").await.expect("write close");

        let mut payload = Vec::new();
        assert_eq!(read_frame(&mut rx, &mut payload).await.expect("f1"), (FRAME_DATA, 8));
        assert_eq!(payload, b"mc bytes");
        assert_eq!(read_frame(&mut rx, &mut payload).await.expect("f2"), (FRAME_DATA, 0));
        assert_eq!(read_frame(&mut rx, &mut payload).await.expect("f3"), (FRAME_CLOSE, 0));
    }

    #[tokio::test]
    async fn oversized_payload_rejected() {
        let (a, _b) = duplex(1024);
        let mut tx = FecStream::new(a);
        let big = vec![0u8; u16::MAX as usize + 1];
        let res = write_frame(&mut tx, FRAME_DATA, &big).await;
        assert!(matches!(&res, Err(e) if e.kind() == io::ErrorKind::InvalidInput));
    }

    #[tokio::test]
    async fn start_eof_maps_to_close() {
        let (a, b) = duplex(64 * 1024);
        drop(a); // 对端直接消失
        let mut rx = FecStream::new(b);
        let mut payload = Vec::new();
        assert_eq!(
            read_frame(&mut rx, &mut payload).await.expect("eof-as-close"),
            (FRAME_CLOSE, 0)
        );
    }
}
