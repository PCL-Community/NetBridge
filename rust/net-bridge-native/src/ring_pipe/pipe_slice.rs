pub struct PipeSlice<'a> {
    slice: &'a mut [u8],
}

impl<'a> PipeSlice<'a> {
    #[inline(always)]
    pub fn slice_mut(&mut self) -> &mut [u8] {
        self.slice
    }

    pub fn ensure(_used: usize) {}
}
