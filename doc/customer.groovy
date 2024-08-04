String getPath() {
    return new File(getClass().protectionDomain.codeSource.location.path).parent
}