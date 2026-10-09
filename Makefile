# JvMumps J0 — build + corpus runner (no Gradle required)
JAVA_HOME ?= /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
JAVAC := $(JAVA_HOME)/bin/javac
RELEASE ?= 21
JAVA  := $(JAVA_HOME)/bin/java
OUT   := build
LIBS  := lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar
CP    := $(OUT):$(LIBS)
CORPUS ?= ../pymumps/tests/conformance.json

.PHONY: all smoke run clean

all: $(OUT)/jvmumps/CorpusRunner.class

$(OUT)/jvmumps/CorpusRunner.class: src/main/java/jvmumps/*.java
	mkdir -p $(OUT)
	$(JAVAC) --release 21 -cp $(LIBS) -d $(OUT) src/main/java/jvmumps/*.java

smoke: all
	$(JAVAC) --release 21 -cp $(CP) -d $(OUT) src/test/java/jvmumps/*.java
	$(JAVA) -cp $(CP) jvmumps.J0Smoke $(CORPUS)
	$(JAVA) -cp $(CP) jvmumps.J1Smoke
	$(JAVA) -cp $(CP) jvmumps.J2Smoke
	$(JAVA) -cp $(CP) jvmumps.J12SymLitSmoke
	$(JAVA) -cp $(CP) jvmumps.J7PersistSmoke

run: all
	$(JAVA) -cp $(CP) jvmumps.CorpusRunner $(CORPUS)

.PHONY: all smoke run clean mjvm bench install uninstall man dist

mjvm: all
	$(JAVAC) --release 21 -cp $(CP) -d $(OUT) src/main/java/mjvm/*/*.java

bench: mjvm
	$(JAVA) -cp $(CP) mjvm.bench.MjvmBench 100000

install:
	PREFIX=$(PREFIX) ./install.sh

uninstall:
	rm -f $(PREFIX)/bin/mjvm $(PREFIX)/share/man/man1/mjvm.1 $(PREFIX)/share/man/man7/jvmumps.7
	rm -rf $(PREFIX)/lib/mjvm

man:
	man ./man/mjvm.1

dist: mjvm
	mkdir -p dist/mjvm-0.1.0
	cp -R build lib bin man docs install.sh README.md CRITICAL_PATH.md dist/mjvm-0.1.0/
	cd dist && tar czf mjvm-0.1.0.tar.gz mjvm-0.1.0
	@echo "dist/mjvm-0.1.0.tar.gz"

clean:
	rm -rf $(OUT) dist
