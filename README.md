# CorrELF

## Setup


### [coderec](https://github.com/vobst/coderec) integration

* Place the platform‐specific [`coderec`](https://github.com/vobst/coderec) executable under

    ```
    src/main/resources/coderec/coderec    # Linux/macOS (chmod +x)  
    src/main/resources/coderec/coderec.exe  # Windows  
    ```
  
* Configure the path in `src/main/resources/application.properties`:

     ```properties
     coderec.location=classpath:coderec/coderec       # Linux/macOS
     coderec.location=classpath:coderec/coderec.exe   # Windows
     ```
