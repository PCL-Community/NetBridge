pub struct PipeSlice<'a> {
    slice: &'a [u8],
}

impl<'a> PipeSlice<'a> {
    #[inline(always)]
    pub fn slice_mut(&mut self) -> &mut [u8] {
        self.slice
    }

    pub fn ensure(used: usize) {}
}
