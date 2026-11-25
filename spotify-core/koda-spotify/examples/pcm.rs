//! The custom sink end to end: decoded PCM leaves librespot through a bounded
//! channel, and a reader that stops pulling makes the sink block.
//!
//! Two modes.
//!
//! Offline, no Spotify account needed, proves the channel's backpressure:
//!
//! ```text
//! cargo run --example pcm -- --self-test
//! ```
//!
//! With a token and a track, proves librespot really drives the sink:
//!
//! ```text
//! cargo run --example pcm -- <ACCESS_TOKEN> <TRACK_URI> [--slow-ms=N] [--client-id=...]
//! ```
//!
//! `--slow-ms` sleeps that long per chunk in the reader. It is there to make the
//! backpressure visible: the sink fills the channel, blocks, and librespot's
//! decode follows the reader's pace instead of running ahead of it. Without it
//! the reader keeps up and the whole track is decoded far faster than real time,
//! which is the fact phase 4 has to handle by pacing reads against playback.

use std::env;
use std::process::ExitCode;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::RecvTimeoutError;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use koda_spotify::pcm::{self, ChannelSink, BYTES_PER_MS, DEFAULT_CHANNEL_CAPACITY_CHUNKS};
use librespot_core::authentication::Credentials;
use librespot_core::config::SessionConfig;
use librespot_core::session::Session;
use librespot_core::spotify_uri::SpotifyUri;
use librespot_playback::audio_backend::Sink;
use librespot_playback::config::PlayerConfig;
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::NoOpVolume;
use librespot_playback::player::Player;

const REQUIRED_SCOPES: &str =
    "streaming,user-read-playback-state,user-modify-playback-state,user-read-currently-playing";

fn usage(program: &str) -> ExitCode {
    eprintln!(
        "Usage: {program} --self-test\n\
         \x20      {program} ACCESS_TOKEN TRACK_URI [--slow-ms=N] [--client-id=...]\n\
         \n\
         ACCESS_TOKEN is a token from your own Spotify app, scoped with:\n\
         \x20 {REQUIRED_SCOPES}"
    );
    ExitCode::from(2)
}

/// Proves the channel is bounded and that a full one blocks the writer, with no
/// Spotify session involved.
fn self_test() -> ExitCode {
    const CAPACITY: usize = 4;
    const CHUNKS: usize = 64;
    const SAMPLES_PER_CHUNK: usize = 1024;

    let (tx, rx) = pcm::channel(CAPACITY);
    let written = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&written);

    let writer = thread::spawn(move || {
        let mut sink = ChannelSink::new(tx);
        let mut converter = Converter::new(None);
        // The last send fails when the reader is gone, which is the expected
        // end of this loop, not an error worth reporting.
        for _ in 0..CHUNKS {
            if sink
                .write(
                    AudioPacket::Samples(vec![0.0; SAMPLES_PER_CHUNK]),
                    &mut converter,
                )
                .is_err()
            {
                break;
            }
            counter.fetch_add(1, Ordering::SeqCst);
        }
    });

    thread::sleep(Duration::from_millis(200));
    let stalled_at = written.load(Ordering::SeqCst);
    println!(
        "reader idle for 200 ms: writer reached {stalled_at} of {CHUNKS} chunks, capacity is {CAPACITY}"
    );

    let started = Instant::now();
    let mut drained_chunks = 0usize;
    let mut drained_bytes = 0usize;
    while drained_chunks < CHUNKS {
        match rx.recv_timeout(Duration::from_secs(2)) {
            Ok(bytes) => {
                drained_bytes += bytes.len();
                drained_chunks += 1;
                thread::sleep(Duration::from_millis(5));
            }
            Err(RecvTimeoutError::Timeout) => break,
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
    let elapsed = started.elapsed();

    // The writer has nothing left to send only once every chunk was drained,
    // so this join is the proof that it woke up and finished.
    let finished = writer.join().is_ok();
    let written_total = written.load(Ordering::SeqCst);

    println!(
        "drained {drained_chunks} chunks, {drained_bytes} bytes in {:.1} s while sleeping 5 ms per chunk",
        elapsed.as_secs_f64()
    );
    println!("writer finished: {finished}, wrote {written_total} chunks");

    let bounded = stalled_at == CAPACITY;
    let flowed = drained_chunks == CHUNKS && written_total == CHUNKS;
    if bounded && flowed {
        println!("PASS: a full channel blocks the sink, and draining it resumes the writer.");
        ExitCode::SUCCESS
    } else {
        eprintln!("FAIL: expected the writer to stop at {CAPACITY} chunks and then finish.");
        ExitCode::FAILURE
    }
}

/// Real playback into the channel, printing what the reader pulls per second.
fn play(access_token: &str, track_uri: &str, slow_ms: u64, client_id: Option<String>) -> ExitCode {
    let track = match SpotifyUri::from_uri(track_uri) {
        Ok(uri @ SpotifyUri::Track { .. }) => uri,
        Ok(_) => {
            eprintln!("Not a track URI: {track_uri}");
            return ExitCode::from(2);
        }
        Err(error) => {
            eprintln!("Could not parse {track_uri}: {error}");
            return ExitCode::from(2);
        }
    };

    let mut session_config = SessionConfig::default();
    if let Some(client_id) = client_id {
        session_config.client_id = client_id;
    }

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            eprintln!("Could not start a runtime: {error}");
            return ExitCode::FAILURE;
        }
    };

    let session = Session::new(session_config, None);
    println!("Connecting with an access token...");
    if let Err(error) =
        runtime.block_on(session.connect(Credentials::with_access_token(access_token), false))
    {
        eprintln!("Login failed: {error}");
        return ExitCode::FAILURE;
    }
    println!("Connected as {:?}", session.username());

    let (tx, rx) = pcm::channel(DEFAULT_CHANNEL_CAPACITY_CHUNKS);
    let player = Player::new(
        PlayerConfig::default(),
        session,
        Box::new(NoOpVolume),
        pcm::sink_factory(tx),
    );

    // The reader is a plain thread: the sink's `send` blocks there, and a
    // blocking read must not sit on the runtime's only worker.
    let reader = thread::spawn(move || {
        let started = Instant::now();
        let mut bytes = 0u64;
        let mut chunks = 0u64;
        let mut last_report = Instant::now();
        loop {
            match rx.recv_timeout(Duration::from_secs(3)) {
                Ok(chunk) => {
                    bytes += chunk.len() as u64;
                    chunks += 1;
                    if slow_ms > 0 {
                        thread::sleep(Duration::from_millis(slow_ms));
                    }
                    if last_report.elapsed() >= Duration::from_secs(1) {
                        print_throughput(bytes, chunks, started.elapsed());
                        last_report = Instant::now();
                    }
                }
                Err(RecvTimeoutError::Timeout) | Err(RecvTimeoutError::Disconnected) => break,
            }
        }
        (bytes, chunks, started.elapsed())
    });

    player.load(track, true, 0);
    println!(
        "Playing into the PCM channel ({} chunks of capacity, {} slow ms per chunk).",
        DEFAULT_CHANNEL_CAPACITY_CHUNKS, slow_ms
    );
    runtime.block_on(player.await_end_of_track());
    // Let the reader empty what is still queued.
    thread::sleep(Duration::from_millis(500));
    drop(player);

    match reader.join() {
        Ok((bytes, chunks, elapsed)) => {
            print_throughput(bytes, chunks, elapsed);
            println!(
                "Done: {bytes} bytes ({:.1} s of audio) in {} chunks, {} ms of audio per wall second.",
                bytes as f64 / BYTES_PER_MS as f64,
                elapsed.as_secs_f64().max(0.001),
                (bytes as f64 / BYTES_PER_MS as f64) / elapsed.as_secs_f64().max(0.001) * 1000.0
            );
            ExitCode::SUCCESS
        }
        Err(_) => {
            eprintln!("The reader thread panicked.");
            ExitCode::FAILURE
        }
    }
}

fn print_throughput(bytes: u64, chunks: u64, elapsed: Duration) {
    let audio_ms = bytes as f64 / BYTES_PER_MS as f64;
    let wall_s = elapsed.as_secs_f64().max(0.001);
    println!(
        "  {chunks} chunks, {bytes} bytes, {audio_ms:.0} ms of audio in {wall_s:.1} s wall ({:.1}x real time)",
        (audio_ms / 1000.0) / wall_s
    );
}

fn main() -> ExitCode {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() == 1 && args[0] == "--self-test" {
        return self_test();
    }

    let mut positional = Vec::new();
    let mut slow_ms = 0u64;
    let mut client_id = None;
    for arg in &args {
        if let Some(value) = arg.strip_prefix("--slow-ms=") {
            slow_ms = value.parse().unwrap_or(0);
        } else if let Some(value) = arg.strip_prefix("--client-id=") {
            client_id = Some(value.to_owned());
        } else if arg.starts_with("--") {
            return usage(&env::args().next().unwrap_or_else(|| "pcm".to_owned()));
        } else {
            positional.push(arg.clone());
        }
    }

    if positional.len() != 2 {
        return usage(&env::args().next().unwrap_or_else(|| "pcm".to_owned()));
    }
    play(&positional[0], &positional[1], slow_ms, client_id)
}
