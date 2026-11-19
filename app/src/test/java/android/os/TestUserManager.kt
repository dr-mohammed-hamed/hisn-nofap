package android.os

class TestUserManager(
    private val profiles: List<UserHandle>
) : UserManager() {
    override fun getUserProfiles(): MutableList<UserHandle> {
        return profiles.toMutableList()
    }
}
