package eu.kanade.tachiyomi.animeextension.en.streamingunity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video

/**
 * Helper activity that can be opened to stream video via an external player.
 */
class StreamingUnityUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent.extras?.getSerializable("video") as? Video
        if (video != null) {
            try {
                // Try to launch external video player
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        android.net.Uri.parse(video.videoUrl),
                        "video/*"
                    )
                    putExtra("force_fullscreen", true)
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.e("StreamingUnity", "No video player found", e)
            }
        }
        finish()
    }
}
