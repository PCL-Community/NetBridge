.PHONY: build debug check clean

build:
	./gradlew assembleAll -PnativeProfile=release

debug:
	./gradlew assembleAll -PnativeProfile=debug

check:
	./gradlew check

clean:
	./gradlew clean
	cd rust/net-bridge-native && cargo clean
