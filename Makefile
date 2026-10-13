# JvMumps J0 — build + corpus runner (no Gradle required)
# Portable JDK: prefer $JAVA_HOME, else javac on PATH (Linux), else Homebrew (Mac).
ifeq ($(origin JAVA_HOME),environment)
  JAVAC := $(JAVA_HOME)/bin/javac
  JAVA  := $(JAVA_HOME)/bin/java
else ifneq ($(wildcard /opt/homebrew/opt/openjdk/bin/javac),)
  JAVA_HOME ?= /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  JAVAC := $(JAVA_HOME)/bin/javac
  JAVA  := $(JAVA_HOME)/bin/java
else
  JAVAC := javac
  JAVA  := java
endif
RELEASE ?= 21
OUT   := build
LIBS  := lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar
CP    := $(OUT):$(LIBS)
CORPUS ?= ../pymumps/tests/conformance.json

# All *Smoke test mains (src/test/java/jvmumps/*Smoke.java). J0 takes CORPUS.
SMOKE_CLASSES := $(patsubst src/test/java/jvmumps/%.java,%,$(wildcard src/test/java/jvmumps/*Smoke.java))
SMOKE_NOARG   := $(filter-out J0Smoke,$(SMOKE_CLASSES))
# Integration: live SqlStore temp files (ad-hoc before #64).
ITEST_CLASSES := SqlEngineSmoke SqlCorpusSmoke

.PHONY: all smoke smokes-core itest run clean

all: $(OUT)/jvmumps/CorpusRunner.class

$(OUT)/jvmumps/CorpusRunner.class: src/main/java/jvmumps/*.java
	mkdir -p $(OUT)
	$(JAVAC) --release 21 -cp $(LIBS) -d $(OUT) src/main/java/jvmumps/*.java

# Full unit/smoke suite — every *Smoke class (jvmumps#64).
smoke: all mjvm
	$(JAVAC) --release 21 -cp $(CP) -d $(OUT) src/test/java/jvmumps/*.java
	@set -e; \
	echo "SMOKE COUNT $(words $(SMOKE_CLASSES))"; \
	$(JAVA) -cp $(CP) jvmumps.J0Smoke $(CORPUS); \
	for c in $(SMOKE_NOARG); do \
	  echo "== $$c =="; \
	  $(JAVA) -cp $(CP) jvmumps.$$c $(CORPUS); \
	done; \
	echo "SMOKE ALL OK $(words $(SMOKE_CLASSES))"

# Fast subset (local loop) — does not replace smoke/CI.
smokes-core: all mjvm
	$(JAVAC) --release 21 -cp $(CP) -d $(OUT) src/test/java/jvmumps/*.java
	$(JAVA) -cp $(CP) jvmumps.J0Smoke $(CORPUS)
	$(JAVA) -cp $(CP) jvmumps.J1Smoke
	$(JAVA) -cp $(CP) jvmumps.J2Smoke
	$(JAVA) -cp $(CP) jvmumps.J6LockSmoke
	$(JAVA) -cp $(CP) jvmumps.JTxLockSemSmoke
	$(JAVA) -cp $(CP) jvmumps.J12SymLitSmoke
	$(JAVA) -cp $(CP) jvmumps.J7PersistSmoke

# Integration (SqlStore / engine over temp DB) — jvmumps#64 itest.
itest: all
	$(JAVAC) --release 21 -cp $(CP) -d $(OUT) src/test/java/jvmumps/*.java
	@set -e; \
	for c in $(ITEST_CLASSES); do \
	  echo "== itest $$c =="; \
	  $(JAVA) -cp $(CP) jvmumps.$$c $(CORPUS); \
	done; \
	echo "ITEST OK $(words $(ITEST_CLASSES))"

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
