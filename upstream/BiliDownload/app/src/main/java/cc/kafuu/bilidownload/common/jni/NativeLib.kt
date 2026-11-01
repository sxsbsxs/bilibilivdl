package cc.kafuu.bilidownload.common.jni

object NativeLib {
    init {
        System.loadLibrary("native_lib")
    }

    external fun generateQrCode(text: String): Pair<BooleanArray, Int>
}