use std::{cell::UnsafeCell, sync::atomic::AtomicU64};

mod error;
mod pipe_slice;

/// No shared cache
#[repr(C, align(64))]
pub struct Au64(AtomicU64);

#[repr(C)]
pub struct Pipe {
    capacity: u64,
    head: Au64,
    tail: Au64,
    buffer: UnsafeCell<Box<[u8]>>,
}

impl Pipe {
    pub fn new(capacity: u64) -> Self {
        Self {
            capacity,
            head: Au64(AtomicU64::new(0)),
            tail: Au64(AtomicU64::new(1)),
            buffer: UnsafeCell::new(vec![0u8; capacity as usize].into_boxed_slice()),
        }
    }

    #[inline(always)]
    pub fn mut_slice(&mut self) -> &mut [u8] {
        self.buffer.get_mut()
    }

    pub fn reader() {}

    pub fn writer() {}
}
