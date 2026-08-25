.PHONY: build debug clean

# 发布构建：Rust release profile 直接调用 cargo（不经过 mise），
# Java/Gradle 走 Gradle Wrapper（./gradlew，JDK 取自 PATH/mise shim）。
build:
	cd rust/net-bridge-native && cargo build --release
	./gradlew build assembleAll -PnativeProfile=release

# 调试构建：cargo 默认（debug）profile + 同一套 gradle 流程。
debug:
	cd rust/net-bridge-native && cargo build
	./gradlew build assembleAll -PnativeProfile=debug

clean:
	cd rust/net-bridge-native && cargo clean
	./gradlew clean
	rm -rf build/native
