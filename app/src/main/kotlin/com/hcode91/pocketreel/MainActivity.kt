package com.hcode91.pocketreel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var entries: List<LibraryEntry> = emptyList()

    private val prefs by lazy { getSharedPreferences("pocketreel", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(BG)
        }

        root.addView(TextView(this).apply {
            text = "PocketReel"
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ACCENT)
        })
        root.addView(TextView(this).apply {
            text = "Player for your VideoNow XP, Color and original disc rips"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill("Library folder") { pickFolder() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * dp).toInt() })
        buttons.addView(pill("Open files") { pickFiles() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
        }
        root.addView(status)

        list = ListView(this).apply {
            divider = null
            dividerHeight = (8 * dp).toInt()
            adapter = LibraryAdapter()
            setOnItemClickListener { _, _, pos, _ -> load(entries[pos].main, entries[pos].siblings) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // A disc asked for by another app is looked up once the library has been scanned.
        pendingLaunch = if (savedInstanceState == null) intent else null
        rescan()
    }

    /** A launch request from another app, waiting for the library scan. */
    private var pendingLaunch: Intent? = null

    /** True while a disc opened for another app is loading: the menu closes once it plays. */
    private var closeAfterLoad = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLaunch = intent
        rescan()
    }

    /**
     * The request another app (BacklogQuest's home-screen tiles) sends to play a disc straight away:
     * extra "game_id", the disc's title as the library lists it, or a disc file as the intent's data.
     */
    private fun launchPending() {
        val i = pendingLaunch ?: return
        pendingLaunch = null
        if (i.action != ACTION_LAUNCH_GAME) return
        val wanted = i.getStringExtra("game_id")?.trim().orEmpty()
        val file = i.data
        when {
            wanted.isNotEmpty() -> {
                val e = entries.firstOrNull { it.title.equals(wanted, ignoreCase = true) }
                if (e == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Disc not found")
                        .setMessage("\"$wanted\" isn't in your library. The name must match the title shown in the list.")
                        .setPositiveButton("OK", null)
                        .show()
                    return
                }
                closeAfterLoad = true
                load(e.main, e.siblings)
            }
            file != null -> {
                closeAfterLoad = true
                load(Docs.describe(contentResolver, file), emptyList())
            }
        }
    }

    private fun pill(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(BG)
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(ACCENT)
        }
        setOnClickListener { onClick() }
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> {
                val tree = data.data ?: return
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(KEY_TREE, tree.toString()).apply()
                rescan()
            }
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                val files = uris.map { Docs.describe(contentResolver, it) }
                val cue = files.firstOrNull { it.ext == "cue" }
                val zip = files.firstOrNull { it.ext == "zip" }
                when {
                    cue != null -> load(cue, files)
                    zip != null -> load(zip, emptyList())
                    files.isNotEmpty() -> load(files.maxBy { it.size }, emptyList())
                }
            }
        }
    }

    private fun rescan() {
        val treeText = prefs.getString(KEY_TREE, null)
        if (treeText == null) {
            status.text = "Choose the folder that holds your disc rips (.cue + .bin, .zip, .bin or .wav), or open files directly."
            launchPending()
            return
        }
        status.text = "Scanning library…"
        val tree = Uri.parse(treeText)
        worker.execute {
            val result = runCatching { Docs.scanLibrary(contentResolver, tree) }
            main.post {
                result.onSuccess {
                    entries = it
                    (list.adapter as BaseAdapter).notifyDataSetChanged()
                    status.text = if (it.isEmpty()) "No discs found in that folder yet." else "${it.size} disc${if (it.size == 1) "" else "s"} in your library"
                }.onFailure {
                    status.text = "Couldn't read the library folder. Pick it again. (${it.message})"
                }
                launchPending()
            }
        }
    }

    private fun load(mainFile: DocFile, siblings: List<DocFile>) {
        val message = TextView(this).apply {
            setPadding(60, 40, 60, 40)
            textSize = 15f
            text = "Loading ${mainFile.name}…"
        }
        val dialog = AlertDialog.Builder(this).setView(message).setCancelable(false).show()
        worker.execute {
            val result = runCatching {
                Docs.mount(this, mainFile, siblings) { text -> main.post { message.text = text } }
            }
            main.post {
                dialog.dismiss()
                result.onSuccess { disc ->
                    DiscSession.replace(disc)
                    startActivity(Intent(this, PlayerActivity::class.java))
                    if (closeAfterLoad) finish()
                }.onFailure { e ->
                    closeAfterLoad = false
                    val hint = if (mainFile.ext == "cue" && siblings.size <= 1)
                        "\n\nWhen opening a .cue, select its .bin files at the same time, or add the folder as your library."
                    else ""
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't open that disc")
                        .setMessage((e.message ?: e.toString()) + hint)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private inner class LibraryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val dp = resources.displayMetrics.density
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(CARD) }
                addView(TextView(context).apply { textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.START })
                addView(TextView(context).apply { textSize = 12f; setTextColor(MUTED) })
            }
            val e = entries[position]
            (row.getChildAt(0) as TextView).text = e.title
            (row.getChildAt(1) as TextView).text = e.kind + if (e.main.size > 0) " · ${e.main.size / 1_000_000} MB" else ""
            return row
        }
    }

    companion object {
        const val ACTION_LAUNCH_GAME = "com.hcode91.pocket.LAUNCH_GAME"
        const val REQ_TREE = 1
        const val REQ_FILES = 2
        const val KEY_TREE = "library_tree"
        val BG = Color.rgb(0x14, 0x0D, 0x26)
        val CARD = Color.rgb(0x2B, 0x1E, 0x4A)
        val ACCENT = Color.rgb(0xFF, 0xB1, 0x3B)
        val MUTED = Color.rgb(0xB8, 0xAE, 0xD0)
    }
}
