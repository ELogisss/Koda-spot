//! Decoded PCM out of librespot and into a bounded channel.
//!
//! librespot pushes samples to a [`Sink`] on its own audio thread. The desktop
//! examples let that thread block, which is the backpressure the engine will
//! rely on later: when Koda's player stops pulling (paused, seeking, a buffer
//! full), the sink blocks, librespot's decoder stops reading ahead, and no
//! unbounded amount of audio accumulates in memory.
//!
//! The channel carries interleaved signed 16-bit little-endian samples,
//! [`CHANNELS`] channels at [`SAMPLE_RATE_HZ`], because that is the shape a WAV
//! stream needs and what an ExoPlayer `AudioTrack` consumes without further
//! conversion. librespot decodes to `f64` and its [`Converter`] scales to S16;
//! the sink does the byte packing.

use std::sync::mpsc::{sync_channel, Receiver, SyncSender};

use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;

/// Sample rate the channel always carries.
pub const SAMPLE_RATE_HZ: u32 = 44_100;
/// Channel count the channel always carries.
pub const CHANNELS: u16 = 2;
/// Bits per sample the channel always carries.
pub const BITS_PER_SAMPLE: u16 = 16;
/// Bytes in one frame (all channels, one sample time), 4 at 16-bit stereo.
pub const BYTES_PER_FRAME: u32 = CHANNELS as u32 * (BITS_PER_SAMPLE as u32 / 8);
/// Bytes one millisecond of this format occupies: 176.
///
/// The WAV header in phase 4 sizes its data chunk as
/// `duration_ms * BYTES_PER_MS`, and the seek maps a byte offset back through
/// the same constant, so keep both of those on this number.
pub const BYTES_PER_MS: u64 = (SAMPLE_RATE_HZ as u64 * BYTES_PER_FRAME as u64) / 1000;

/// Backpressure in one number: how many decoded chunks may sit between
/// librespot and the reader before the sink blocks.
///
/// Decoder packets are a few thousand samples (tens of milliseconds), so 32
/// chunks is roughly one second of audio: enough to ride out scheduling on the
/// reader without buffering audio nobody is about to play.
pub const DEFAULT_CHANNEL_CAPACITY_CHUNKS: usize = 32;

/// A sink that converts to S16 and hands the bytes to a bounded channel.
///
/// `send` blocks while the channel is full; that is deliberate. A closed
/// receiver is reported as [`SinkError::NotConnected`], which stops librespot's
/// playback instead of silently dropping audio.
pub struct ChannelSink {
    tx: SyncSender<Vec<u8>>,
}

impl ChannelSink {
    pub fn new(tx: SyncSender<Vec<u8>>) -> Self {
        Self { tx }
    }
}

impl Sink for ChannelSink {
    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let bytes = match packet {
            AudioPacket::Samples(samples) => {
                let pcm = converter.f64_to_s16(&samples);
                let mut out = Vec::with_capacity(pcm.len() * 2);
                for sample in pcm {
                    out.extend_from_slice(&sample.to_le_bytes());
                }
                out
            }
            // Raw packets only arrive with librespot's passthrough decoder,
            // which this crate never enables: its bytes are the encoded file,
            // not playable PCM.
            AudioPacket::Raw(_) => {
                return Err(SinkError::InvalidParams(
                    "raw audio packet without the passthrough decoder".to_owned(),
                ))
            }
        };

        self.tx
            .send(bytes)
            .map_err(|_| SinkError::NotConnected("PCM receiver has been dropped".to_owned()))
    }
}

/// Build the channel the sink writes into and the reader pulls from.
pub fn channel(capacity_chunks: usize) -> (SyncSender<Vec<u8>>, Receiver<Vec<u8>>) {
    sync_channel(capacity_chunks)
}

/// The closure `Player::new` wants, over a channel built by [`channel`].
pub fn sink_factory(tx: SyncSender<Vec<u8>>) -> impl FnOnce() -> Box<dyn Sink> + Send + 'static {
    move || Box::new(ChannelSink::new(tx))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use std::thread;
    use std::time::Duration;

    fn packet(samples: usize) -> AudioPacket {
        AudioPacket::Samples(vec![0.0; samples])
    }

    #[test]
    fn sink_writes_interleaved_s16_bytes() {
        let (tx, rx) = channel(4);
        let mut sink = ChannelSink::new(tx);
        let mut converter = Converter::new(None);

        sink.write(packet(8), &mut converter).unwrap();

        let bytes = rx.recv().unwrap();
        // 8 samples at 16 bits, little-endian.
        assert_eq!(bytes.len(), 16);
    }

    #[test]
    fn closed_receiver_stops_the_sink() {
        let (tx, rx) = channel(1);
        drop(rx);
        let mut sink = ChannelSink::new(tx);
        let mut converter = Converter::new(None);

        assert!(matches!(
            sink.write(packet(8), &mut converter),
            Err(SinkError::NotConnected(_))
        ));
    }

    #[test]
    fn a_full_channel_blocks_the_sink_until_the_reader_drains() {
        const CAPACITY: usize = 4;
        const CHUNKS: usize = 64;
        let (tx, rx) = channel(CAPACITY);
        let written = Arc::new(AtomicUsize::new(0));

        let counter = Arc::clone(&written);
        let writer = thread::spawn(move || {
            let mut sink = ChannelSink::new(tx);
            let mut converter = Converter::new(None);
            for _ in 0..CHUNKS {
                sink.write(packet(1024), &mut converter).unwrap();
                counter.fetch_add(1, Ordering::SeqCst);
            }
        });

        // With nobody reading, the writer stops at the channel's capacity.
        thread::sleep(Duration::from_millis(200));
        assert_eq!(written.load(Ordering::SeqCst), CAPACITY);
        assert!(!writer.is_finished());

        // Draining one chunk frees exactly one slot, and the writer runs again.
        rx.recv().unwrap();
        thread::sleep(Duration::from_millis(100));
        assert_eq!(written.load(Ordering::SeqCst), CAPACITY + 1);

        drop(rx);
        // Dropping the reader wakes the blocked send, which now fails.
        let result = writer.join();
        assert!(result.is_err() || written.load(Ordering::SeqCst) < CHUNKS);
    }
}
