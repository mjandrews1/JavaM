# JvMumps J0 — build + corpus runner (no Gradle required)
JAVA_HOME ?= /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
JAVAC := $(JAVA_HOME)/bin/javac
JAVA  := $(JAVA_HOME)/bin/java
OUT   := build
LIBS  := lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar
CP    := $(OUT):$(LIBS)
CORPUS ?= ../pymumps/tests/conformance.json

.PHONY: all smoke run clean

all: $(OUT)/jvmumps/CorpusRunner.class

$(OUT)/jvmumps/CorpusRunner.class: src/main/java/jvmumps/*.java
	mkdir -p $(OUT)
	$(JAVAC) -cp $(LIBS) -d $(OUT) src/main/java/jvmumps/*.java

smoke: all
	$(JAVAC) -cp $(CP) -d $(OUT) src/test/java/jvmumps/*.java
	$(JAVA) -cp $(CP) jvmumps.J0Smoke $(CORPUS)
	$(JAVA) -cp $(CP) jvmumps.J1Smoke
	$(JAVA) -cp $(CP) jvmumps.J2Smoke
	$(JAVA) -cp $(CP) jvmumps.J7PersistSmoke

run: all
	$(JAVA) -cp $(CP) jvmumps.CorpusRunner $(CORPUS)

clean:
	rm -rf $(OUT)

.PHONY: install man
install:
	PREFIX=$(PREFIX) ./install.sh

man:
	man ./man/mjvm.1
