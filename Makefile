.PHONY: build debug clean

# 发布构建：Rust release profile 直接调用 cargo（不经过 mise），
# Java/Gradle 走 mise 管理的工具链。
build:
	cd rust/qmc-native && cargo build --release
	mise exec -- gradle build assembleAll -PnativeProfile=release

# 调试构建：cargo 默认（debug）profile + 同一套 gradle 流程。
debug:
	cd rust/qmc-native && cargo build
	mise exec -- gradle build assembleAll -PnativeProfile=debug

clean:
	cd rust/qmc-native && cargo clean
	mise exec -- gradle clean
	rm -rf build/native
