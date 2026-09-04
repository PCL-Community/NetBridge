//! bridge 面常量测试：ABI 版本与状态码契约。

use super::STATE_CONNECTED;

#[test]
fn abi_constants() {
    assert_eq!(STATE_CONNECTED, 1);
}
