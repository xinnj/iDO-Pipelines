@GrabResolver(name='jitpack.io', root='https://jitpack.io/')
@Grab(group = "com.github.kenglxn.QRGen", module = "javase", version = "2.6.0")
import static net.glxn.qrgen.core.image.ImageType.PNG
import static net.glxn.qrgen.javase.QRCode.from

String call(String text) {
    ByteArrayOutputStream stream = from(text).to(PNG).stream();
    return stream.toByteArray().encodeBase64().toString()
}
