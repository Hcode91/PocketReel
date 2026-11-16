package com.hcode91.pocketreel.tools

import com.hcode91.pocketreel.core.*
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

/**
 * Desktop test harness for the PocketReel core.
 *
 *   info  <disc>                          track table
 *   frames <disc> <track> <outDir> <f...> write frames as PNG
 *   run   <disc> <outDir> <seconds> [t:slot ...]
 *         play headless, pressing button slot (1-6) at time t seconds;
 *         writes run.wav and a PNG every second plus at each press
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: info|frames|run <disc.cue|.zip|.bin> ...")
        return
    }
    val disc = openAny(File(args[1]))
    when (args[0]) {
        "info" -> info(disc)
        "frames" -> frames(disc, args[2].toInt(), File(args[3]), args.drop(4).map { it.toInt() })
        "run" -> run(disc, File(args[2]), args[3].toDouble(), args.drop(4))
        else -> println("unknown command")
    }
    disc.close()
}

private fun openAny(file: File): DiscImage {
    if (file.extension.equals("zip", true)) {
        val dir = File(System.getProperty("java.io.tmpdir"), "pocketreel-" + file.nameWithoutExtension.hashCode())
        val target = if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true)
            dir.listFiles()!!.firstOrNull { it.extension == "cue" } ?: dir.listFiles()!!.maxBy { it.length() }
        else file.inputStream().use { DiscLoader.extractZip(it, dir) }
        return DiscLoader.open(target)
    }
    return DiscLoader.open(file)
}

private fun info(disc: DiscImage) {
    val player = VideoNowPlayer(disc)
    println("Disc: ${disc.name}  layout=${player.layout}  ${player.outputSampleRate} Hz  ${"%.3f".format(player.layout.playbackFrameRate)} fps")
    for (t in player.trackInfos()) {
        val br = t.branches.joinToString("  ") { b ->
            "${b.slot + 1}:${b.playList.joinToString(">")}" + if (b.tag != 0) "(>=%02X)".format(b.tag) else ""
        }
        println(
            "%3d %-6s %6d fr %7.1fs %-13s next=%-3d %s%s  %s".format(
                t.number, t.format.shortName, t.frameCount, t.durationSeconds, t.kind, t.continueTrack,
                if (t.blank) "[blank] " else "", br, t.title ?: "",
            ),
        )
    }
}

private fun frames(disc: DiscImage, track: Int, out: File, list: List<Int>) {
    out.mkdirs()
    val t = disc.findTrack(track)!!
    val layout = FormatDetector.detect(t)!!
    val reader = TrackReader(t, layout)
    val argb = IntArray(layout.pixelCount)
    for (f in list) {
        val frame = reader.readFrame(f) ?: continue
        VideoDecoder.decode(frame, argb)
        save(argb, layout.pictureWidth, layout.pictureHeight, File(out, "t${track}_f$f.png"))
        val h = frame.readHeader()
        println("frame $f: kind=${h.kind} idx=${h.frameIndex}/${h.frameCount} trackReg=${h.trackNumber} branches=${h.branches}")
    }
}

private fun run(disc: DiscImage, out: File, seconds: Double, presses: List<String>) {
    out.mkdirs()
    val player = VideoNowPlayer(disc)
    val rate = player.outputSampleRate
    val schedule = presses.map { it.split(':').let { p -> p[0].toDouble() to p[1].toInt() - 1 } }.sortedBy { it.first }.toMutableList()
    var lastTrack = -1
    player.onTrackChanged = { p -> println("  -> track ${p.currentTrack}  score=%02X".format(p.score)) }
    player.play()
    val chunk = ShortArray(rate / 10)
    val wav = RandomAccessFile(File(out, "run.wav"), "rw")
    wav.setLength(0)
    wav.write(ByteArray(44))
    var total = 0L
    var shot = 0
    val bytes = ByteArray(chunk.size * 2)
    while (total < seconds * rate && player.state == TransportState.Playing) {
        val now = total / rate.toDouble()
        while (schedule.isNotEmpty() && schedule[0].first <= now) {
            val (_, slot) = schedule.removeAt(0)
            val ok = player.press(slot)
            println("%.1fs press ${slot + 1} on track ${player.currentTrack}: ${if (ok) "taken" else "not offered"} (offered ${player.activeSlots.map { it + 1 }})".format(now))
            save(player.framebuffer, player.pictureWidth, player.pictureHeight, File(out, "press_%03d.png".format(shot++)))
        }
        player.renderAudio(chunk)
        for (i in chunk.indices) { bytes[i * 2] = chunk[i].toInt().toByte(); bytes[i * 2 + 1] = (chunk[i].toInt() shr 8).toByte() }
        wav.write(bytes)
        total += chunk.size
        if (total % rate == 0L) {
            val sec = total / rate
            if (player.currentTrack != lastTrack || sec % 5 == 0L) {
                save(player.framebuffer, player.pictureWidth, player.pictureHeight, File(out, "s%04d_t%d.png".format(sec, player.currentTrack)))
                lastTrack = player.currentTrack
            }
            if (player.isChoicePoint) println("${sec}s track ${player.currentTrack} frame ${player.currentFrame} choice ${player.activeSlots.map { it + 1 }}")
        }
    }
    val dataLen = (total * 2).toInt()
    wav.seek(0)
    fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    wav.write("RIFF".toByteArray()); wav.write(le32(36 + dataLen)); wav.write("WAVEfmt ".toByteArray())
    wav.write(le32(16)); wav.write(le16(1)); wav.write(le16(1)); wav.write(le32(rate)); wav.write(le32(rate * 2))
    wav.write(le16(2)); wav.write(le16(16)); wav.write("data".toByteArray()); wav.write(le32(dataLen))
    wav.close()
    println("ended in state ${player.state} on track ${player.currentTrack}")
}

private fun save(argb: IntArray, w: Int, h: Int, file: File) {
    val img = BufferedImage(w * 4, h * 4, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h * 4) for (x in 0 until w * 4) img.setRGB(x, y, argb[(y / 4) * w + x / 4])
    ImageIO.write(img, "png", file)
}
