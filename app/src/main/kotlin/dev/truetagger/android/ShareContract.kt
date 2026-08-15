package dev.truetagger.android

object ShareContract {
    const val PREFS = "truetagger"
    const val PREF_DOWNLOAD_TREE = "download_tree"
    const val PREF_AUTO_REFRESH_LIBRARY = "auto_refresh_library"
    const val PREF_EMBED_YOUTUBE_THUMBNAIL = "embed_youtube_thumbnail"
    const val PREF_AUTO_UPDATE_DOWNLOADER = "auto_update_downloader"

    const val EXTRA_SHARED_URL = "shared_url"
    const val ACTION_CONFIGURE_SHARE = "dev.truetagger.action.CONFIGURE_SHARE"
    const val ACTION_START_DOWNLOAD = "dev.truetagger.action.START_DOWNLOAD"
    const val ACTION_OPEN_APP = "dev.truetagger.action.OPEN_APP"

    const val NOTIFICATION_CHANNEL_ID = "truetagger_downloads"
    const val NOTIFICATION_ID = 201
    const val NOTIFICATION_RESULT_ID = 202
}
