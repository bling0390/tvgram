package tv.telegram.ui.nav

object Routes {
    const val QR_LOGIN = "qrLogin"
    const val HOME = "home"
    const val HOME_SEARCH = "home/search"
    const val HOME_CHATS = "home/chats"
    const val HOME_SETTINGS = "home/settings"
    const val PLAYER = "player/{index}"

    fun player(index: Int): String = "player/$index"
}
