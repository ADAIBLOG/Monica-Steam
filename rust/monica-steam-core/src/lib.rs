pub mod achievement;
pub mod auth_session;
pub mod authorized_device;
pub mod chat;
pub mod cm;
pub mod family;
pub mod friend_nickname;
pub mod group_chat;
pub mod group_summary;
pub mod library;
pub mod login_auth;
pub mod ma_file;
pub mod proto;
pub mod trade_offer;
pub mod two_factor;
pub mod wishlist;

use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::Sha256;

const STEAM_CODE_CHARS: &[u8] = b"23456789BCDFGHJKMNPQRTVWXY";
const STEAM_CODE_PERIOD_SECONDS: i64 = 30;
const MAX_CONFIRMATION_TAG_CHARS: usize = 32;

type HmacSha1 = Hmac<Sha1>;
type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SteamCoreError {
    EmptySecret,
    InvalidBase64Secret,
    EmptyDecodedSecret,
    HmacInitialization,
}

pub fn generate_auth_code(
    shared_secret_base64: &str,
    unix_time_seconds: i64,
) -> Result<String, SteamCoreError> {
    let key = decode_secret(shared_secret_base64)?;
    let counter = unix_time_seconds.div_euclid(STEAM_CODE_PERIOD_SECONDS) as u64;
    let digest = hmac_sha1(&key, &counter.to_be_bytes())?;
    let start = (digest[19] & 0x0f) as usize;
    let mut full_code = ((digest[start] as u32 & 0x7f) << 24)
        | ((digest[start + 1] as u32) << 16)
        | ((digest[start + 2] as u32) << 8)
        | digest[start + 3] as u32;

    let mut code = String::with_capacity(5);
    for _ in 0..5 {
        let index = (full_code % STEAM_CODE_CHARS.len() as u32) as usize;
        code.push(STEAM_CODE_CHARS[index] as char);
        full_code /= STEAM_CODE_CHARS.len() as u32;
    }
    Ok(code)
}

pub fn generate_confirmation_hash(
    identity_secret_base64: &str,
    unix_time_seconds: i64,
    tag: &str,
) -> Result<String, SteamCoreError> {
    let key = decode_secret(identity_secret_base64)?;
    let truncated_tag: String = tag.chars().take(MAX_CONFIRMATION_TAG_CHARS).collect();
    let mut payload = Vec::with_capacity(8 + truncated_tag.len());
    payload.extend_from_slice(&(unix_time_seconds as u64).to_be_bytes());
    payload.extend_from_slice(truncated_tag.as_bytes());
    Ok(BASE64.encode(hmac_sha1(&key, &payload)?))
}

pub fn generate_login_approval_signature(
    shared_secret_base64: &str,
    version: i32,
    client_id: i64,
    steam_id: i64,
) -> Result<Vec<u8>, SteamCoreError> {
    let key = decode_secret(shared_secret_base64)?;
    let mut payload = Vec::with_capacity(18);
    payload.extend_from_slice(&(version as u16).to_le_bytes());
    payload.extend_from_slice(&client_id.to_le_bytes());
    payload.extend_from_slice(&steam_id.to_le_bytes());
    Ok(hmac_sha256(&key, &payload)?.to_vec())
}

pub fn generate_login_token_signature(
    shared_secret_base64: &str,
    token_id: i64,
) -> Result<Vec<u8>, SteamCoreError> {
    let key = decode_secret(shared_secret_base64)?;
    Ok(hmac_sha256(&key, &token_id.to_le_bytes())?.to_vec())
}

pub fn seconds_remaining(unix_time_seconds: i64) -> i32 {
    let elapsed = unix_time_seconds.rem_euclid(STEAM_CODE_PERIOD_SECONDS);
    (STEAM_CODE_PERIOD_SECONDS - elapsed) as i32
}

fn decode_secret(encoded_secret: &str) -> Result<Vec<u8>, SteamCoreError> {
    let normalized = encoded_secret.trim();
    if normalized.is_empty() {
        return Err(SteamCoreError::EmptySecret);
    }
    let decoded = BASE64
        .decode(normalized)
        .map_err(|_| SteamCoreError::InvalidBase64Secret)?;
    if decoded.is_empty() {
        return Err(SteamCoreError::EmptyDecodedSecret);
    }
    Ok(decoded)
}

fn hmac_sha1(key: &[u8], payload: &[u8]) -> Result<[u8; 20], SteamCoreError> {
    let mut mac = HmacSha1::new_from_slice(key).map_err(|_| SteamCoreError::HmacInitialization)?;
    mac.update(payload);
    let bytes = mac.finalize().into_bytes();
    let mut output = [0u8; 20];
    output.copy_from_slice(&bytes);
    Ok(output)
}

fn hmac_sha256(key: &[u8], payload: &[u8]) -> Result<[u8; 32], SteamCoreError> {
    let mut mac = HmacSha256::new_from_slice(key).map_err(|_| SteamCoreError::HmacInitialization)?;
    mac.update(payload);
    let bytes = mac.finalize().into_bytes();
    let mut output = [0u8; 32];
    output.copy_from_slice(&bytes);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_code_matches_kotlin_reference_vector() {
        assert_eq!(
            generate_auth_code("dGVzdC1zZWNyZXQ=", 1_700_000_000).unwrap(),
            "2Q9K4"
        );
    }

    #[test]
    fn confirmation_hash_matches_kotlin_reference_vectors() {
        assert_eq!(
            generate_confirmation_hash("aWRlbnRpdHktc2VjcmV0", 1_700_000_000, "conf").unwrap(),
            "3chEG7VC5Xu8Gq4ReM/JybXVhrY="
        );
        assert_eq!(
            generate_confirmation_hash("aWRlbnRpdHktc2VjcmV0", 1_700_000_000, "allow").unwrap(),
            "iNx1Np0OF7LQP1OxGT+WU1Z8Gdc="
        );
    }

    #[test]
    fn login_approval_signatures_match_kotlin_reference_vectors() {
        assert_eq!(
            BASE64.encode(generate_login_approval_signature(
                "dGVzdC1zZWNyZXQ=",
                2,
                123_456_789,
                765_611_980_000_000_00,
            ).unwrap()),
            "Scb6ui+6HFveSYL6MbIrNAzXfuhng+9K7VajaeQEEQo="
        );
        assert_eq!(
            BASE64.encode(generate_login_token_signature(
                "dGVzdC1zZWNyZXQ=",
                9_876_543_210,
            ).unwrap()),
            "LEm+11pdUeceV05WidIwDVWE91YBjmMNRYqNMd6c4/Q="
        );
    }

    #[test]
    fn remaining_seconds_uses_steam_period() {
        assert_eq!(seconds_remaining(1_700_000_000), 10);
        assert_eq!(seconds_remaining(30), 30);
    }

    #[test]
    fn invalid_secrets_fail_without_panicking() {
        assert_eq!(generate_auth_code("", 0), Err(SteamCoreError::EmptySecret));
        assert_eq!(
            generate_auth_code("not base64", 0),
            Err(SteamCoreError::InvalidBase64Secret)
        );
    }
}
