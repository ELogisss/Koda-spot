//! Desktop proof that a Spotify access token, Client ID and scopes are enough
//! to stream a track through librespot, with librespot's own default sink.
//!
//! This is the phase 2 checkpoint made runnable:
//!
//! ```text
//! cargo run --features desktop-audio --example play -- <ACCESS_TOKEN> <TRACK_URI> [CLIENT_ID]
//! ```
//!
//! The token comes from the user's own Spotify Developer app, minted with the
//! scopes in [`REQUIRED_SCOPES`]. `CLIENT_ID` overrides the session's client id
//! when the token was issued to an app librespot does not know about.
//!
//! It logs in, loads one track URI, plays it to the machine's default output and
//! waits for the end of the track. Nothing is cached to disk: the session is
//! built without a `Cache`, so credentials and audio both stay in memory.

use std::env;
use std::process::ExitCode;

use librespot_core::authentication::Credentials;
use librespot_core::config::SessionConfig;
use librespot_core::session::Session;
use librespot_core::spotify_uri::SpotifyUri;
use librespot_playback::audio_backend;
use librespot_playback::config::{AudioFormat, PlayerConfig};
use librespot_playback::mixer::NoOpVolume;
use librespot_playback::player::Player;

/// Scopes the token needs: `streaming` to receive audio, and the playback-state
/// scopes librespot's own `get_token` example asks for so a session can report
/// and control what it plays.
const REQUIRED_SCOPES: &str =
    "streaming,user-read-playback-state,user-modify-playback-state,user-read-currently-playing";

fn usage(program: &str) {
    eprintln!(
        "Usage: {program} ACCESS_TOKEN TRACK_URI [CLIENT_ID]\n\
         \n\
         ACCESS_TOKEN  a token from your own Spotify app, scoped with:\n\
         \x20             {REQUIRED_SCOPES}\n\
         TRACK_URI     spotify:track:<base62 id>\n\
         CLIENT_ID     optional; the app the token was issued to"
    );
}

#[tokio::main]
async fn main() -> ExitCode {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let args: Vec<String> = env::args().collect();
    if args.len() < 3 || args.len() > 4 {
        usage(&args[0]);
        return ExitCode::from(2);
    }
    let access_token = &args[1];
    let track = match SpotifyUri::from_uri(&args[2]) {
        Ok(uri @ SpotifyUri::Track { .. }) => uri,
        Ok(_) => {
            eprintln!("Not a track URI: {}", args[2]);
            return ExitCode::from(2);
        }
        Err(error) => {
            eprintln!("Could not parse {}: {error}", args[2]);
            return ExitCode::from(2);
        }
    };

    let mut session_config = SessionConfig::default();
    if let Some(client_id) = args.get(3) {
        session_config.client_id = client_id.clone();
    }

    let session = Session::new(session_config, None);
    println!("Connecting with an access token...");
    if let Err(error) = session
        .connect(Credentials::with_access_token(access_token), false)
        .await
    {
        eprintln!("Login failed: {error}");
        eprintln!("A non-Premium account, a token without the streaming scope and a revoked token all land here.");
        return ExitCode::FAILURE;
    }
    println!("Connected as {:?}", session.username());

    let audio_format = AudioFormat::S16;
    let backend = match audio_backend::find(None) {
        Some(backend) => backend,
        None => {
            eprintln!("This build has no audio backend; run it with --features desktop-audio.");
            return ExitCode::FAILURE;
        }
    };

    let player = Player::new(
        PlayerConfig::default(),
        session,
        Box::new(NoOpVolume),
        move || backend(None, audio_format),
    );

    player.load(track, true, 0);
    println!("Playing; the default sink is the system's output. Ctrl-C to stop.");
    player.await_end_of_track().await;
    println!("Done.");
    ExitCode::SUCCESS
}
