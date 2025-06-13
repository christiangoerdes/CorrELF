# CorrELF

## Setup

### JNI-based `coderec` library integration

1. Build the JNI library:
   ```bash
   git clone https://github.com/christiangoerdes/coderec.git
   cd coderec
   curl --proto '=https' --tlsv1.2 -sSf https://valentinobst.de/a13f15d91f0f8846d748e42e7a881f783eb8f922861a63d9dfb74824d21337039dd8216f0373c3e5820c5e32de8f0a1880ec55456ff0da39f17d32f567d62b84/cpu_rec_corpus.tar.gz -o cpu_rec_corpus.tar.gz && tar xf cpu_rec_corpus.tar.gz && rm cpu_rec_corpus.tar.gz
   cargo build --release
   ```
2. Place the compiled JNI library under `src/main/resources/coderec/`:
   ```
   coderec/coderec_jni.dll       # Windows
   coderec/coderec_jni.dylib     # macOS
   coderec/libcoderec_jni.so     # Linux
   ```
