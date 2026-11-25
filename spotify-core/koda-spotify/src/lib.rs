//! Koda's Spotify engine, built on librespot.
//!
//! Phase 2 of the Spotify plan is a proof of concept: a desktop example that
//! logs in with a Spotify access token and plays a track, a custom sink that
//! hands decoded PCM to a bounded channel, and a cross-compiled Android
//! library for both supported ABIs. The FFI layer, the player wrapper and the
//! Media3 `DataSource` follow in later phases.
//!
//! Nothing here touches the network on its own, and nothing is loaded until a
//! caller asks for it, so a build with Spotify mode off costs an unused `.so`.

pub mod pcm;
