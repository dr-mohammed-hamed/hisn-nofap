package android.net

import android.os.Parcel

/**
 * JVM test double for [Uri] (whose constructor is package-private in `android.net`).
 */
class TestUri(
    private val schemeValue: String?,
    private val hostValue: String?
) : Uri() {
    override fun getScheme(): String? = schemeValue
    override fun getHost(): String? = hostValue
    override fun isHierarchical(): Boolean = true
    override fun isOpaque(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun isAbsolute(): Boolean = true
    override fun getSchemeSpecificPart(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getAuthority(): String? = hostValue
    override fun getEncodedAuthority(): String? = hostValue
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getLastPathSegment(): String? = null
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun toString(): String = "${schemeValue.orEmpty()}://${hostValue.orEmpty()}"
    override fun equals(other: Any?): Boolean =
        other is TestUri && schemeValue == other.schemeValue && hostValue == other.hostValue
    override fun hashCode(): Int = 31 * (schemeValue?.hashCode() ?: 0) + (hostValue?.hashCode() ?: 0)
    override fun compareTo(other: Uri): Int = toString().compareTo(other.toString())
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}
}
