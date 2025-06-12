# CorrELF

## Setup

### JNA-based `coderec` library integration

1. Build the JNA library:
   ```bash
   git clone https://github.com/christiangoerdes/coderec.git
   cd coderec
   cargo build --release
   ```
2. Copy the built lib into the project resources

3. Point Spring at the library in `application.properties`:
   ```properties
   coderec.location=classpath:coderec/coderec_jna.dll       # Windows
   coderec.location=classpath:coderec/libcoderec_jna.so     # Linux
   coderec.location=classpath:coderec/libcoderec_jna.dylib  # macOS
   ```