use base64::{
    engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE, URL_SAFE_NO_PAD},
    Engine as _,
};

use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef, ProtoWriter};

const MAX_AUTH_PAYLOAD_BYTES: usize = 8 * 1024 * 1024;
const MAX_AUTH_CHALLENGES: usize = 128;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoginAuthChallenge {
    pub confirmation_type: i32,
    pub associated_message: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BeginCredentialsResponse {
    pub client_id: String,
    pub request_id: String,
    pub steam_id: String,
    pub challenges: Vec<LoginAuthChallenge>,
    pub message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BeginQrResponse {
    pub client_id: String,
    pub request_id: String,
    pub challenge_url: String,
    pub challenges: Vec<LoginAuthChallenge>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PollAuthResponse {
    pub client_id: Option<String>,
    pub challenge_url: Option<String>,
    pub refresh_token: Option<String>,
    pub access_token: Option<String>,
    pub account_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AccessTokenResponse {
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LoginAuthError {
    Proto(ProtoError),
    PayloadTooLarge,
    InvalidTimestamp,
    InvalidClientId,
    InvalidSteamId,
    InvalidTokenId,
    InvalidRequestId,
    IncompleteResponse,
    TooManyChallenges,
}

impl From<ProtoError> for LoginAuthError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn build_begin_credentials_request(
    device_friendly_name: &str,
    user_name: &str,
    encrypted_password: &str,
    encryption_timestamp: &str,
    platform_type: i64,
    os_type: i64,
    gaming_device_type: i64,
    website_id: &str,
) -> Result<Vec<u8>, LoginAuthError> {
    let timestamp = encryption_timestamp
        .trim()
        .parse::<u64>()
        .map_err(|_| LoginAuthError::InvalidTimestamp)?;
    let device = build_device_details(
        device_friendly_name,
        platform_type,
        os_type,
        gaming_device_type,
    )?;
    let mut request = ProtoWriter::new();
    request.write_string(1, device_friendly_name)?;
    request.write_string(2, user_name)?;
    request.write_string(3, encrypted_password)?;
    request.write_uint64(4, timestamp)?;
    request.write_bool(5, false)?;
    request.write_varint(6, platform_type)?;
    request.write_varint(7, 1)?;
    request.write_string(8, website_id)?;
    request.write_message(9, &device)?;
    request.write_string(10, "")?;
    request.write_varint(11, 0)?;
    request.write_varint(12, 2)?;
    Ok(request.into_bytes())
}

pub fn build_begin_qr_request(
    device_friendly_name: &str,
    platform_type: i64,
    os_type: i64,
    gaming_device_type: i64,
    website_id: &str,
) -> Result<Vec<u8>, LoginAuthError> {
    let device = build_device_details(
        device_friendly_name,
        platform_type,
        os_type,
        gaming_device_type,
    )?;
    let mut request = ProtoWriter::new();
    request.write_string(1, device_friendly_name)?;
    request.write_varint(2, 3)?;
    request.write_message(3, &device)?;
    request.write_string(4, website_id)?;
    Ok(request.into_bytes())
}

pub fn build_update_guard_request(
    client_id: &str,
    steam_id: &str,
    code: &str,
    confirmation_type: i32,
) -> Result<Vec<u8>, LoginAuthError> {
    let client_id = parse_u64(client_id).ok_or(LoginAuthError::InvalidClientId)?;
    let steam_id = parse_u64(steam_id).ok_or(LoginAuthError::InvalidSteamId)?;
    let mut request = ProtoWriter::new();
    request.write_uint64(1, client_id)?;
    request.write_fixed64(2, steam_id as i64)?;
    request.write_string(3, code.trim())?;
    request.write_varint(4, confirmation_type as i64)?;
    Ok(request.into_bytes())
}

pub fn build_poll_request(
    client_id: &str,
    request_id: &str,
    token_to_revoke: Option<&str>,
) -> Result<Vec<u8>, LoginAuthError> {
    let client_id = parse_u64(client_id).ok_or(LoginAuthError::InvalidClientId)?;
    let request_id = decode_request_id(request_id).ok_or(LoginAuthError::InvalidRequestId)?;
    let mut request = ProtoWriter::new();
    request.write_uint64(1, client_id)?;
    request.write_bytes(2, &request_id)?;
    if let Some(token_id) = token_to_revoke {
        let token_id = parse_u64(token_id).ok_or(LoginAuthError::InvalidTokenId)?;
        request.write_fixed64(3, token_id as i64)?;
    }
    Ok(request.into_bytes())
}

pub fn build_generate_access_token_request(
    refresh_token: &str,
    steam_id: &str,
) -> Result<Vec<u8>, LoginAuthError> {
    let steam_id = parse_u64(steam_id).ok_or(LoginAuthError::InvalidSteamId)?;
    let mut request = ProtoWriter::new();
    request.write_string(1, refresh_token)?;
    request.write_fixed64(2, steam_id as i64)?;
    Ok(request.into_bytes())
}

pub fn parse_begin_credentials_response(
    response: &[u8],
) -> Result<BeginCredentialsResponse, LoginAuthError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let client_id = first_nonzero_u64(&fields, 1)
        .map(|value| value.to_string())
        .ok_or(LoginAuthError::IncompleteResponse)?;
    let request_id = first_kotlin_bytes(&fields, 2)
        .filter(|value| !value.is_empty())
        .map(|value| STANDARD.encode(value))
        .ok_or(LoginAuthError::IncompleteResponse)?;
    let steam_id = first_nonzero_u64(&fields, 5)
        .map(|value| value.to_string())
        .ok_or(LoginAuthError::IncompleteResponse)?;
    let message = first_string(&fields, 8).filter(|value| !value.trim().is_empty());
    Ok(BeginCredentialsResponse {
        client_id,
        request_id,
        steam_id,
        challenges: parse_challenges(&fields, 4)?,
        message,
    })
}

pub fn parse_begin_qr_response(response: &[u8]) -> Result<BeginQrResponse, LoginAuthError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let client_id = first_nonzero_u64(&fields, 1)
        .map(|value| value.to_string())
        .ok_or(LoginAuthError::IncompleteResponse)?;
    let challenge_url = first_string(&fields, 2)
        .filter(|value| !value.trim().is_empty())
        .ok_or(LoginAuthError::IncompleteResponse)?;
    let request_id = first_kotlin_bytes(&fields, 3)
        .filter(|value| !value.is_empty())
        .map(|value| STANDARD.encode(value))
        .ok_or(LoginAuthError::IncompleteResponse)?;
    Ok(BeginQrResponse {
        client_id,
        request_id,
        challenge_url,
        challenges: parse_challenges(&fields, 5)?,
    })
}

pub fn parse_poll_response(response: &[u8]) -> Result<PollAuthResponse, LoginAuthError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    Ok(PollAuthResponse {
        client_id: last_nonzero_u64(&fields, 1).map(|value| value.to_string()),
        challenge_url: last_string(&fields, 2).filter(|value| !value.trim().is_empty()),
        refresh_token: last_string(&fields, 3).filter(|value| !value.trim().is_empty()),
        access_token: last_string(&fields, 4).filter(|value| !value.trim().is_empty()),
        account_name: last_string(&fields, 6).filter(|value| !value.trim().is_empty()),
    })
}

pub fn parse_generate_access_token_response(
    response: &[u8],
) -> Result<AccessTokenResponse, LoginAuthError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    Ok(AccessTokenResponse {
        access_token: last_string(&fields, 1).filter(|value| !value.trim().is_empty()),
        refresh_token: last_string(&fields, 2).filter(|value| !value.trim().is_empty()),
    })
}

pub fn normalize_session_ids(
    client_id: &str,
    request_id: &str,
    steam_id: Option<&str>,
) -> Result<(u64, Vec<u8>, Option<u64>), LoginAuthError> {
    let client_id = parse_u64(client_id).ok_or(LoginAuthError::InvalidClientId)?;
    let request_id = decode_request_id(request_id).ok_or(LoginAuthError::InvalidRequestId)?;
    let steam_id = match steam_id.map(str::trim).filter(|value| !value.is_empty()) {
        Some(value) => Some(parse_u64(value).ok_or(LoginAuthError::InvalidSteamId)?),
        None => None,
    };
    Ok((client_id, request_id, steam_id))
}

fn build_device_details(
    device_friendly_name: &str,
    platform_type: i64,
    os_type: i64,
    gaming_device_type: i64,
) -> Result<ProtoWriter, ProtoError> {
    let mut device = ProtoWriter::new();
    device.write_string(1, device_friendly_name)?;
    device.write_varint(2, platform_type)?;
    device.write_varint(3, os_type)?;
    device.write_varint(4, gaming_device_type)?;
    Ok(device)
}

fn ensure_payload_size(response: &[u8]) -> Result<(), LoginAuthError> {
    if response.len() > MAX_AUTH_PAYLOAD_BYTES {
        Err(LoginAuthError::PayloadTooLarge)
    } else {
        Ok(())
    }
}

fn parse_challenges(
    fields: &[ProtoFieldRef<'_>],
    confirmation_field: u32,
) -> Result<Vec<LoginAuthChallenge>, LoginAuthError> {
    let mut challenges = Vec::new();
    for field in fields.iter().filter(|field| field.number == confirmation_field) {
        if challenges.len() >= MAX_AUTH_CHALLENGES {
            return Err(LoginAuthError::TooManyChallenges);
        }
        let challenge = match field.value {
            ProtoValueRef::Bytes(bytes) => parse_challenge(bytes),
            ProtoValueRef::Fixed64(value) => parse_challenge(&value.to_le_bytes()),
            ProtoValueRef::Fixed32(value) => parse_challenge(&value.to_le_bytes()),
            ProtoValueRef::Varint(_) => None,
        };
        if let Some(challenge) = challenge {
            challenges.push(challenge);
        }
    }
    Ok(challenges)
}

fn parse_challenge(data: &[u8]) -> Option<LoginAuthChallenge> {
    let fields = parse_all_ref(data).ok()?;
    let confirmation_type = last_field(&fields, 1)
        .map(kotlin_as_int)
        .unwrap_or(0);
    if confirmation_type == 0 {
        return None;
    }
    Some(LoginAuthChallenge {
        confirmation_type,
        associated_message: last_field(&fields, 2)
            .map(kotlin_as_string)
            .unwrap_or_default(),
    })
}

fn parse_u64(value: &str) -> Option<u64> {
    value.trim().parse::<u64>().ok()
}

fn decode_request_id(value: &str) -> Option<Vec<u8>> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    let compact: String = trimmed
        .chars()
        .filter(|value| !value.is_whitespace())
        .collect();
    for engine in [&STANDARD, &STANDARD_NO_PAD, &URL_SAFE, &URL_SAFE_NO_PAD] {
        if let Ok(decoded) = engine.decode(compact.as_bytes()) {
            if !decoded.is_empty() {
                return Some(decoded);
            }
        }
    }
    decode_hex(trimmed).or_else(|| Some(trimmed.as_bytes().to_vec()))
}

fn decode_hex(value: &str) -> Option<Vec<u8>> {
    if value.is_empty()
        || value.len() % 2 != 0
        || !value.bytes().all(|byte| byte.is_ascii_hexdigit())
    {
        return None;
    }
    let mut decoded = Vec::with_capacity(value.len() / 2);
    for chunk in value.as_bytes().chunks_exact(2) {
        let high = hex_value(chunk[0])?;
        let low = hex_value(chunk[1])?;
        decoded.push((high << 4) | low);
    }
    (!decoded.is_empty()).then_some(decoded)
}

fn hex_value(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn first_nonzero_u64(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<u64> {
    fields
        .iter()
        .find(|field| field.number == number)
        .and_then(kotlin_as_u64)
        .filter(|value| *value != 0)
}

fn last_nonzero_u64(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<u64> {
    last_field(fields, number)
        .and_then(kotlin_as_u64)
        .filter(|value| *value != 0)
}

fn first_kotlin_bytes(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<Vec<u8>> {
    let field = fields.iter().find(|field| field.number == number)?;
    match field.value {
        ProtoValueRef::Varint(_) => None,
        ProtoValueRef::Bytes(bytes) => Some(bytes.to_vec()),
        ProtoValueRef::Fixed64(value) => Some(value.to_le_bytes().to_vec()),
        ProtoValueRef::Fixed32(value) => Some(value.to_le_bytes().to_vec()),
    }
}

fn first_string(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<String> {
    fields
        .iter()
        .find(|field| field.number == number)
        .map(kotlin_as_string)
}

fn last_string(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<String> {
    last_field(fields, number).map(kotlin_as_string)
}

fn last_field<'fields, 'data>(
    fields: &'fields [ProtoFieldRef<'data>],
    number: u32,
) -> Option<&'fields ProtoFieldRef<'data>> {
    fields.iter().rev().find(|field| field.number == number)
}

fn kotlin_as_u64(field: &ProtoFieldRef<'_>) -> Option<u64> {
    match field.value {
        ProtoValueRef::Varint(value) => Some(value),
        _ => None,
    }
}

fn kotlin_as_int(field: &ProtoFieldRef<'_>) -> i32 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64 as i32,
        _ => 0,
    }
}

fn kotlin_as_string(field: &ProtoFieldRef<'_>) -> String {
    match field.value {
        ProtoValueRef::Varint(_) => String::new(),
        ProtoValueRef::Bytes(bytes) => String::from_utf8_lossy(bytes).into_owned(),
        ProtoValueRef::Fixed64(value) => String::from_utf8_lossy(&value.to_le_bytes()).into_owned(),
        ProtoValueRef::Fixed32(value) => String::from_utf8_lossy(&value.to_le_bytes()).into_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{parse_all, ProtoValue};

    const DEVICE_NAME: &str = "Monica Steam";
    const WEBSITE_ID: &str = "Mobile";

    fn profile() -> (&'static str, i64, i64, i64, &'static str) {
        (DEVICE_NAME, 3, -500, 528, WEBSITE_ID)
    }

    fn nested_challenge(kind: i64, message: &str) -> ProtoWriter {
        let mut challenge = ProtoWriter::new();
        challenge.write_varint(1, kind).unwrap();
        challenge.write_string(2, message).unwrap();
        challenge
    }

    #[test]
    fn credentials_request_matches_kotlin_field_shape() {
        let (device, platform, os, gaming, website) = profile();
        let request = build_begin_credentials_request(
            device,
            "alice",
            "encrypted",
            "1700000000",
            platform,
            os,
            gaming,
            website,
        )
        .unwrap();
        let fields = parse_all(&request).unwrap();
        assert_eq!(fields.len(), 12);
        assert_eq!(fields[0].number, 1);
        assert_eq!(fields[0].as_utf8_lossy().as_deref(), Some(DEVICE_NAME));
        assert_eq!(fields[1].as_utf8_lossy().as_deref(), Some("alice"));
        assert_eq!(fields[2].as_utf8_lossy().as_deref(), Some("encrypted"));
        assert_eq!(fields[3].as_i64(), Some(1_700_000_000));
        assert_eq!(fields[5].as_i64(), Some(3));
        assert_eq!(fields[6].as_i64(), Some(1));
        assert_eq!(fields[7].as_utf8_lossy().as_deref(), Some(WEBSITE_ID));
        let device_bytes = fields[8].as_bytes().unwrap();
        let device_fields = parse_all(device_bytes).unwrap();
        assert_eq!(device_fields[0].as_utf8_lossy().as_deref(), Some(DEVICE_NAME));
        assert_eq!(device_fields[1].as_i64(), Some(3));
        assert_eq!(device_fields[2].as_i64(), Some(-500));
        assert_eq!(device_fields[3].as_i64(), Some(528));
    }

    #[test]
    fn qr_request_matches_kotlin_field_shape() {
        let (device, platform, os, gaming, website) = profile();
        let request = build_begin_qr_request(device, platform, os, gaming, website).unwrap();
        let fields = parse_all(&request).unwrap();
        assert_eq!(fields.len(), 4);
        assert_eq!(fields[0].as_utf8_lossy().as_deref(), Some(DEVICE_NAME));
        assert_eq!(fields[1].as_i64(), Some(3));
        assert_eq!(fields[3].as_utf8_lossy().as_deref(), Some(WEBSITE_ID));
    }

    #[test]
    fn begin_credentials_uses_first_top_level_fields_and_keeps_challenge_order() {
        let mut response = ProtoWriter::new();
        response.write_uint64(1, u64::MAX).unwrap();
        response.write_uint64(1, 7).unwrap();
        response.write_bytes(2, b"request").unwrap();
        let mut first = nested_challenge(3, "device");
        first.write_string(2, "last-message").unwrap();
        response.write_message(4, &first).unwrap();
        response.write_message(4, &nested_challenge(2, "email")).unwrap();
        response.write_fixed64(5, 76_561_198_000_000_000i64).unwrap();
        response.write_uint64(5, 76_561_198_000_000_000).unwrap();
        response.write_string(8, "hello").unwrap();

        // Kotlin reads field 5 via asLong, which is only populated for varints.
        // A fixed64 first duplicate therefore makes the required field incomplete.
        assert_eq!(
            parse_begin_credentials_response(response.as_bytes()),
            Err(LoginAuthError::IncompleteResponse)
        );

        let mut valid = ProtoWriter::new();
        valid.write_uint64(1, u64::MAX).unwrap();
        valid.write_uint64(1, 7).unwrap();
        valid.write_bytes(2, b"request").unwrap();
        valid.write_message(4, &first).unwrap();
        valid.write_message(4, &nested_challenge(2, "email")).unwrap();
        valid.write_uint64(5, 76_561_198_000_000_000).unwrap();
        valid.write_string(8, "hello").unwrap();
        let parsed = parse_begin_credentials_response(valid.as_bytes()).unwrap();
        assert_eq!(parsed.client_id, u64::MAX.to_string());
        assert_eq!(parsed.steam_id, "76561198000000000");
        assert_eq!(parsed.request_id, STANDARD.encode(b"request"));
        assert_eq!(parsed.challenges.len(), 2);
        assert_eq!(parsed.challenges[0].confirmation_type, 3);
        assert_eq!(parsed.challenges[0].associated_message, "last-message");
        assert_eq!(parsed.challenges[1].confirmation_type, 2);
        assert_eq!(parsed.message.as_deref(), Some("hello"));
    }

    #[test]
    fn fixed_request_id_matches_kotlin_bytes_property() {
        let mut response = ProtoWriter::new();
        response.write_uint64(1, 7).unwrap();
        response.write_fixed32(2, 0x0403_0201).unwrap();
        response.write_uint64(5, 76_561_198_000_000_000).unwrap();
        let parsed = parse_begin_credentials_response(response.as_bytes()).unwrap();
        assert_eq!(parsed.request_id, STANDARD.encode([1u8, 2, 3, 4]));
    }

    #[test]
    fn poll_response_uses_last_duplicates_like_kotlin_parse() {
        let mut response = ProtoWriter::new();
        response.write_uint64(1, 7).unwrap();
        response.write_uint64(1, u64::MAX).unwrap();
        response.write_string(2, "old-url").unwrap();
        response.write_string(2, "new-url").unwrap();
        response.write_string(3, "refresh").unwrap();
        response.write_string(4, "access").unwrap();
        response.write_string(6, "alice").unwrap();
        let parsed = parse_poll_response(response.as_bytes()).unwrap();
        assert_eq!(parsed.client_id.as_deref(), Some("18446744073709551615"));
        assert_eq!(parsed.challenge_url.as_deref(), Some("new-url"));
        assert_eq!(parsed.refresh_token.as_deref(), Some("refresh"));
        assert_eq!(parsed.access_token.as_deref(), Some("access"));
        assert_eq!(parsed.account_name.as_deref(), Some("alice"));
    }

    #[test]
    fn request_id_accepts_base64_urlsafe_hex_and_raw_fallback() {
        assert_eq!(decode_request_id("cmVxdWVzdA==").unwrap(), b"request");
        assert_eq!(decode_request_id("_w").unwrap(), vec![0xff]);
        // Android Base64 accepts many alphabetic strings, so use a value that
        // cannot decode as Base64 to exercise the raw fallback explicitly.
        assert_eq!(decode_request_id("0f:1a").unwrap(), b"0f:1a");
        assert_eq!(decode_hex("0f1a").unwrap(), vec![0x0f, 0x1a]);
    }

    #[test]
    fn guard_poll_and_access_token_requests_keep_unsigned_bits() {
        let guard = build_update_guard_request(
            "18446744073709551615",
            "76561198000000000",
            " ABCDE ",
            3,
        )
        .unwrap();
        let fields = parse_all(&guard).unwrap();
        assert!(matches!(fields[0].value, ProtoValue::Varint(u64::MAX)));
        assert_eq!(fields[2].as_utf8_lossy().as_deref(), Some("ABCDE"));

        let poll = build_poll_request(
            "18446744073709551615",
            "cmVxdWVzdA==",
            Some("18446744073709551614"),
        )
        .unwrap();
        let fields = parse_all(&poll).unwrap();
        assert!(matches!(fields[0].value, ProtoValue::Varint(u64::MAX)));
        assert_eq!(fields[1].as_bytes(), Some(b"request".as_slice()));
        assert!(matches!(fields[2].value, ProtoValue::Fixed64(value) if value == u64::MAX - 1));

        let token = build_generate_access_token_request("refresh", "76561198000000000").unwrap();
        let fields = parse_all(&token).unwrap();
        assert_eq!(fields[0].as_utf8_lossy().as_deref(), Some("refresh"));
        assert!(matches!(fields[1].value, ProtoValue::Fixed64(76_561_198_000_000_000)));
    }

    #[test]
    fn access_token_response_uses_last_duplicate_fields() {
        let mut response = ProtoWriter::new();
        response.write_string(1, "old-access").unwrap();
        response.write_string(1, "access").unwrap();
        response.write_string(2, "refresh").unwrap();
        let parsed = parse_generate_access_token_response(response.as_bytes()).unwrap();
        assert_eq!(parsed.access_token.as_deref(), Some("access"));
        assert_eq!(parsed.refresh_token.as_deref(), Some("refresh"));
    }
}
