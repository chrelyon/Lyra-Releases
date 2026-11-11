package android.util
object Base64 {
 const val NO_WRAP=2
 const val URL_SAFE=8
 fun encodeToString(bytes: ByteArray, flags: Int): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
