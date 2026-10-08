package androidx.media3.exoplayer.audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test-only access to the package-private processor used by Media3's float-output pipeline. */
public final class Media3Pcm24FloatTestBridge {
  private Media3Pcm24FloatTestBridge() {}

  public static ByteBuffer convert(ByteBuffer source) throws AudioProcessor.UnhandledAudioFormatException {
    ToFloatPcmAudioProcessor processor = new ToFloatPcmAudioProcessor();
    try {
      processor.configure(new AudioProcessor.AudioFormat(176_400, 2, C.ENCODING_PCM_24BIT));
      processor.flush();
      processor.queueInput(source);

      ByteBuffer output = processor.getOutput().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
      ByteBuffer copy = ByteBuffer.allocateDirect(output.remaining()).order(ByteOrder.nativeOrder());
      copy.put(output);
      copy.flip();
      return copy;
    } finally {
      processor.reset();
    }
  }
}
