// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.*
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.logging.Logger
var videoCount = 0
fun hwDecoderInit(avCodecContext: AVCodecContext, type: Int) {
    val avBufferRef = AVBufferRef(null)
    if (av_hwdevice_ctx_create(avBufferRef, type, null as BytePointer?, AVDictionary(null), 0) < 0) {
        throw RuntimeException("Failed to create specified HW device.")
    }
    avCodecContext.hw_device_ctx(avBufferRef)
}

@Composable
fun Player(url: String, modifier: Modifier = Modifier, loop: Boolean = false) {
    videoCount ++
    val index = videoCount
    val bitmap by produceState<Bitmap?>(null) {
        val logger = Logger.getLogger("Player")

        logger.info("play $url")
        delay(1)
        // 一个format上下文
        val avFormatContext = AVFormatContext(null)
        // 打开视频文件
        if (avformat_open_input(avFormatContext, url, null, null) < 0) {
            value = null
            return@produceState
        }
        logger.info("opened $url")
        // 找到视频流
        if (avformat.avformat_find_stream_info(avFormatContext, null as PointerPointer<*>?) < 0) {
            value = null
            return@produceState
        }
        logger.info("found stream info $url")

        // 一个文件可能有多个流，找到视频流的索引
        var videoIndex = -1
        (0 until avFormatContext.nb_streams()).forEach {
            if (avFormatContext.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                videoIndex = it
                return@forEach
            }
        }
        if (videoIndex < 0) {
            value = null
            return@produceState
        }
        logger.info("found video stream $url")

        // 一个codec上下文
        val avCodecContext = avcodec_alloc_context3(null)
        // 从流中获取codec参数
        avcodec_parameters_to_context(avCodecContext, avFormatContext.streams(videoIndex).codecpar())
        // 找到解码器
        val codec = avcodec_find_decoder(avFormatContext.streams(videoIndex).codecpar().codec_id())
        if (codec == null) {
            value = null
            return@produceState
        }

        // 打开解码器
        if (avcodec_open2(avCodecContext, codec, null as PointerPointer<*>?) < 0) {
            value = null
            return@produceState
        }

        //  解码得到的视频帧
        val frame = av_frame_alloc()
        val pFrameRGB = av_frame_alloc()
        //  解码得到的包
        val packet = AVPacket()

        // 计算每一个frame的大小
        val numBytes = av_image_get_buffer_size(
            AV_PIX_FMT_RGBA, avCodecContext.width(),
            avCodecContext.height(), 1
        )
        // 一个缓存
        val buffer = BytePointer(av_malloc(numBytes.toLong()))
        val swsContext: SwsContext? = sws_getContext(
            avCodecContext.width(),
            avCodecContext.height(),
            avCodecContext.pix_fmt(),
            avCodecContext.width(),
            avCodecContext.height(),
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            null,
            null,
            null as DoublePointer?
        )

        if (swsContext == null) {
            println("swsContext is null")
            value = null
            return@produceState
        }

        av_image_fill_arrays(
            frame.data(), frame.linesize(),
            buffer, avCodecContext.pix_fmt(), avCodecContext.width(), avCodecContext.height(), 1
        )
        av_image_fill_arrays(
            pFrameRGB.data(), pFrameRGB.linesize(),
            buffer, AV_PIX_FMT_RGBA, avCodecContext.width(), avCodecContext.height(), 1
        )

        logger.info("start decoding $url")
        var ret1 = -1;
        var ret2 = -2;
        while (av_read_frame(avFormatContext, packet) >= 0 && isActive) {
            if (packet.stream_index() == videoIndex) {
                ret1 = avcodec_send_packet(avCodecContext, packet)
                ret2 = avcodec_receive_frame(avCodecContext, frame)

                if (ret2 >= 0 && ret1 >= 0) {
                    // 转换frame成统一的颜色格式
                    sws_scale(
                        swsContext,
                        frame.data(),
                        frame.linesize(),
                        0,
                        avCodecContext.height(),
                        pFrameRGB.data(),
                        pFrameRGB.linesize()
                    )
                    // 释放bitmap图片
                    if (value?.isClosed?.not() == true) value!!.close()
                    value = null
//                    if(index <= 4)
                    value = saveFrameToBitmap(pFrameRGB, avCodecContext.width(), avCodecContext.height())
                }

                delay(5)
            }

            // 重置packet
            av_packet_unref(packet)
        }
        logger.info("end decoding $url")

        av_free(buffer)
        av_frame_free(frame)
        av_frame_free(pFrameRGB)
        sws_freeContext(swsContext)
        avcodec_close(avCodecContext)
        avcodec_free_context(avCodecContext)
        avformat_close_input(avFormatContext)
        avformat_free_context(avFormatContext)

        frame.close()
        pFrameRGB.close()
        packet.close()
        buffer.close()
        swsContext.close()
        codec.close()
        avCodecContext.close()
        avFormatContext.close()
        logger.info("closed $url")
        println(packet.address())
    }

    Card(modifier = modifier) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asComposeImageBitmap(), contentDescription = null)
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

class Dome {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            application {
                Window(onCloseRequest = ::exitApplication, title = "ffmpeg dome") {
                    val url = if (args.isNotEmpty()) args[0] else "https://sagiri.me/images/62748309_p0%20.png"
                    Column(
                        Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Row {
                            val modifier = Modifier
                                .weight(1f)
                            Player(url, modifier = modifier)
                            Player(url, modifier = modifier)
                            Player(url, modifier = modifier)
                        }
//
                        Row {
                            val modifier = Modifier
                                .weight(1f)
                            Player(url, modifier = modifier)
                            Player(url, modifier = modifier)
                            Player(url, modifier = modifier)
                        }
                    }
                }
            }
        }

        private fun saveFrame(frame: AVFrame, width: Int, height: Int): ByteArray {
            val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Argb8888, true)

            val byteBuffer = ByteBuffer.allocate(width * height * 4 + 2048)
            byteBuffer.put(String.format("P6\n%d %d\n255\n", width, height).encodeToByteArray())
            val data = frame.data(0)
            val bytes = ByteArray(width * 4)
            val l: Int = frame.linesize(0)
            for (y in 0 until height) {
                data.position((y * l).toLong())[bytes]
                byteBuffer.put(bytes)
            }

            File("qwq${Date().time}.ppm").writeBytes(byteBuffer.array())
            return byteBuffer.array()
        }
    }
}

fun saveFrameToBitmap(frame: AVFrame, width: Int, height: Int): Bitmap {
    val data = frame.data(0)
    val bytes = ByteArray(width * 4)
    data.position(0)[bytes]
    val pixmap = Pixmap.make(
        ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB), width, height),
        frame.data(0).position(0).address(),
        frame.linesize(0),
    )
    frame.data().close()
    frame.linesize().close()
    frame.data(0).close()
    val bitmap = Bitmap.makeFromImage(Image.makeFromPixmap(pixmap))

    data.close()
    bytes.clone()
    if (pixmap.buffer.isClosed.not()) pixmap.buffer.close()
    if (pixmap.isClosed.not()) pixmap.close()
    return bitmap
}