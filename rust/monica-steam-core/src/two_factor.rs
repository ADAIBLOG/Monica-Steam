use base64::{engine::general_purpose::STANDARD, Engine as _};

use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef, ProtoWriter};

const MAX_TWO_FACTOR_PAYLOAD_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AuthenticatorData {
    pub shared_secret: Option<String>,
    pub serial_number: Option<String>,
    pub revocation_code: Option<String>,
    pub uri: Option<String>,
    pub server_time: Option<i64>,
    pub account_name: Option<String>,
    pub token_gid: Option<String>,
    pub identity_secret: Option<String>,
    pub secret_1: Option<String>,
    pub status: i32,
    pub phone_hint: String,
    pub confirm_type: i32,
    pub steamguard_scheme: Option<i32>,
    pub steam_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct FinalizeAuthenticatorData {
    pub success: bool,
    pub want_more: bool,
    pub status: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TwoFactorError {
    Proto(ProtoError),
    PayloadTooLarge,
    InvalidSteamId,
    MissingReplacementPayload,
    InvalidReplacementPayload,
}

impl From<ProtoError> for TwoFactorError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn build_add_authenticator_request(
    steam_id: &str,
    auth_time: i64,
    device_id: &str,
) -> Result<Vec<u8>, TwoFactorError> {
    let steam_id = steam_id
        .trim()
        .parse::<u64>()
        .map_err(|_| TwoFactorError::InvalidSteamId)?;
    let mut request = ProtoWriter::new();
    request.write_fixed64(1, steam_id as i64)?;
    request.write_uint64(2, auth_time as u64)?;
    request.write_varint(4, 1)?;
    request.write_string(5, device_id)?;
    request.write_string(6, "1")?;
    request.write_varint(8, 2)?;
    Ok(request.into_bytes())
}

pub fn parse_add_authenticator_response(
    response: &[u8],
) -> Result<AuthenticatorData, TwoFactorError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    Ok(parse_authenticator_fields(&fields))
}

pub fn build_finalize_authenticator_request(
    steam_id: &str,
    authenticator_code: &str,
    auth_time: i64,
    activation_code: &str,
    validate_sms_code: bool,
) -> Result<Vec<u8>, TwoFactorError> {
    let steam_id = steam_id
        .trim()
        .parse::<u64>()
        .map_err(|_| TwoFactorError::InvalidSteamId)?;
    let mut request = ProtoWriter::new();
    request.write_fixed64(1, steam_id as i64)?;
    request.write_string(2, authenticator_code)?;
    request.write_uint64(3, auth_time as u64)?;
    request.write_string(4, activation_code)?;
    request.write_bool(6, validate_sms_code)?;
    Ok(request.into_bytes())
}

pub fn parse_finalize_authenticator_response(
    response: &[u8],
) -> Result<FinalizeAuthenticatorData, TwoFactorError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    Ok(FinalizeAuthenticatorData {
        success: last_field(&fields, 1).map(kotlin_as_bool).unwrap_or(false),
        want_more: last_field(&fields, 2).map(kotlin_as_bool).unwrap_or(false),
        status: last_field(&fields, 4).map(kotlin_as_int).unwrap_or(0),
    })
}

pub fn build_replace_continue_request(code: &str) -> Result<Vec<u8>, TwoFactorError> {
    let mut request = ProtoWriter::new();
    request.write_string(1, code.trim())?;
    request.write_bool(2, true)?;
    request.write_varint(3, 2)?;
    Ok(request.into_bytes())
}

pub fn parse_replace_continue_response(
    response: &[u8],
) -> Result<AuthenticatorData, TwoFactorError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let replacement = last_field(&fields, 2).ok_or(TwoFactorError::MissingReplacementPayload)?;
    let bytes = kotlin_bytes(replacement).ok_or(TwoFactorError::MissingReplacementPayload)?;
    let replacement_fields = parse_all_ref(&bytes).map_err(|_| TwoFactorError::InvalidReplacementPayload)?;
    Ok(parse_authenticator_fields(&replacement_fields))
}

fn parse_authenticator_fields(fields: &[ProtoFieldRef<'_>]) -> AuthenticatorData {
    AuthenticatorData {
        shared_secret: last_field(fields, 1)
            .and_then(kotlin_bytes)
            .filter(|value| !value.is_empty())
            .map(|value| STANDARD.encode(value)),
        serial_number: last_field(fields, 2)
            .and_then(kotlin_fixed64_unsigned)
            .filter(|value| *value != 0)
            .map(|value| value.to_string()),
        revocation_code: last_nonblank_string(fields, 3),
        uri: last_nonblank_string(fields, 4),
        server_time: last_field(fields, 5).and_then(kotlin_as_long),
        account_name: last_nonblank_string(fields, 6),
        token_gid: last_nonblank_string(fields, 7),
        identity_secret: last_field(fields, 8)
            .and_then(kotlin_bytes)
            .filter(|value| !value.is_empty())
            .map(|value| STANDARD.encode(value)),
        secret_1: last_field(fields, 9)
            .and_then(kotlin_bytes)
            .filter(|value| !value.is_empty())
            .map(|value| STANDARD.encode(value)),
        status: last_field(fields, 10).map(kotlin_as_int).unwrap_or(0),
        phone_hint: last_field(fields, 11)
            .map(kotlin_as_string)
            .unwrap_or_default(),
        confirm_type: last_field(fields, 12).map(kotlin_as_int).unwrap_or(0),
        steamguard_scheme: last_field(fields, 11)
            .map(kotlin_as_int)
            .filter(|value| *value != 0),
        steam_id: last_field(fields, 12)
            .and_then(kotlin_fixed64_unsigned)
            .filter(|value| *value != 0)
            .map(|value| value.to_string()),
    }
}

fn ensure_payload_size(response: &[u8]) -> Result<(), TwoFactorError> {
    if response.len() > MAX_TWO_FACTOR_PAYLOAD_BYTES {
        Err(TwoFactorError::PayloadTooLarge)
    } else {
        Ok(())
    }
}

fn last_nonblank_string(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<String> {
    last_field(fields, number)
        .map(kotlin_as_string)
        .filter(|value| !value.trim().is_empty())
}

fn last_field<'fields, 'data>(
    fields: &'fields [ProtoFieldRef<'data>],
    number: u32,
) -> Option<&'fields ProtoFieldRef<'data>> {
    fields.iter().rev().find(|field| field.number == number)
}

fn kotlin_as_int(field: &ProtoFieldRef<'_>) -> i32 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64 as i32,
        _ => 0,
    }
}

fn kotlin_as_long(field: &ProtoFieldRef<'_>) -> Option<i64> {
    match field.value {
        ProtoValueRef::Varint(value) => Some(value as i64),
        _ => None,
    }
}

fn kotlin_as_bool(field: &ProtoFieldRef<'_>) -> bool {
    kotlin_as_long(field).unwrap_or(0) != 0
}

fn kotlin_as_string(field: &ProtoFieldRef<'_>) -> String {
    match field.value {
        ProtoValueRef::Varint(_) => String::new(),
        ProtoValueRef::Bytes(bytes) => String::from_utf8_lossy(bytes).into_owned(),
        ProtoValueRef::Fixed64(value) => String::from_utf8_lossy(&value.to_le_bytes()).into_owned(),
        ProtoValueRef::Fixed32(value) => String::from_utf8_lossy(&value.to_le_bytes()).into_owned(),
    }
}

fn kotlin_bytes(field: &ProtoFieldRef<'_>) -> Option<Vec<u8>> {
    match field.value {
        ProtoValueRef::Varint(_) => None,
        ProtoValueRef::Bytes(bytes) => Some(bytes.to_vec()),
        ProtoValueRef::Fixed64(value) => Some(value.to_le_bytes().to_vec()),
        ProtoValueRef::Fixed32(value) => Some(value.to_le_bytes().to_vec()),
    }
}

fn kotlin_fixed64_unsigned(field: &ProtoFieldRef<'_>) -> Option<u64> {
    let bytes = kotlin_bytes(field)?;
    if bytes.len() < 8 {
        return Some(0);
    }
    let mut fixed = [0u8; 8];
    fixed.copy_from_slice(&bytes[..8]);
    Some(u64::from_le_bytes(fixed))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{parse_all, ProtoValue};

    #[test]
    fn add_authenticator_request_matches_kotlin_shape() {
        let request = build_add_authenticator_request(
            "76561198000000000",
            1_700_000_000,
            "android:abc",
        )
        .unwrap();
        let fields = parse_all(&request).unwrap();
        assert!(matches!(fields[0].value, ProtoValue::Fixed64(76_561_198_000_000_000)));
        assert_eq!(fields[1].as_i64(), Some(1_700_000_000));
        assert_eq!(fields[2].as_i64(), Some(1));
        assert_eq!(fields[3].as_utf8_lossy().as_deref(), Some("android:abc"));
        assert_eq!(fields[4].as_utf8_lossy().as_deref(), Some("1"));
        assert_eq!(fields[5].as_i64(), Some(2));
    }

    #[test]
    fn add_response_preserves_last_duplicate_and_binary_fields() {
        let secret = [7u8; 20];
        let identity = [8u8; 20];
        let mut response = ProtoWriter::new();
        response.write_bytes(1, b"old").unwrap();
        response.write_bytes(1, &secret).unwrap();
        response.write_fixed64(2, -1).unwrap();
        response.write_string(3, "R123").unwrap();
        response.write_string(4, "otpauth://example").unwrap();
        response.write_uint64(5, 1_700_000_000).unwrap();
        response.write_string(6, "alice").unwrap();
        response.write_string(7, "gid").unwrap();
        response.write_bytes(8, &identity).unwrap();
        response.write_bytes(9, b"secret-one").unwrap();
        response.write_varint(10, 1).unwrap();
        response.write_string(11, "+1 **12").unwrap();
        response.write_varint(12, 3).unwrap();

        let parsed = parse_add_authenticator_response(response.as_bytes()).unwrap();
        assert_eq!(parsed.shared_secret.as_deref(), Some(STANDARD.encode(secret).as_str()));
        assert_eq!(parsed.serial_number.as_deref(), Some("18446744073709551615"));
        assert_eq!(parsed.revocation_code.as_deref(), Some("R123"));
        assert_eq!(parsed.server_time, Some(1_700_000_000));
        assert_eq!(parsed.account_name.as_deref(), Some("alice"));
        assert_eq!(parsed.identity_secret.as_deref(), Some(STANDARD.encode(identity).as_str()));
        assert_eq!(parsed.status, 1);
        assert_eq!(parsed.phone_hint, "+1 **12");
        assert_eq!(parsed.confirm_type, 3);
    }

    #[test]
    fn finalize_request_and_response_match_kotlin_semantics() {
        let request = build_finalize_authenticator_request(
            "76561198000000000",
            "ABCDE",
            1_700_000_000,
            "12345",
            true,
        )
        .unwrap();
        let fields = parse_all(&request).unwrap();
        assert_eq!(fields[1].as_utf8_lossy().as_deref(), Some("ABCDE"));
        assert_eq!(fields[3].as_utf8_lossy().as_deref(), Some("12345"));
        assert_eq!(fields[4].as_i64(), Some(1));

        let mut response = ProtoWriter::new();
        response.write_bool(1, false).unwrap();
        response.write_bool(1, true).unwrap();
        response.write_bool(2, true).unwrap();
        response.write_varint(4, 88).unwrap();
        let parsed = parse_finalize_authenticator_response(response.as_bytes()).unwrap();
        assert!(parsed.success);
        assert!(parsed.want_more);
        assert_eq!(parsed.status, 88);
    }

    #[test]
    fn replace_continue_parses_nested_authenticator_payload() {
        let mut replacement = ProtoWriter::new();
        replacement.write_bytes(1, &[1u8; 20]).unwrap();
        replacement.write_fixed64(2, 42).unwrap();
        replacement.write_string(3, "R1").unwrap();
        replacement.write_varint(5, 1_700_000_000).unwrap();
        replacement.write_string(6, "alice").unwrap();
        replacement.write_varint(10, 1).unwrap();
        replacement.write_varint(11, 2).unwrap();
        replacement.write_fixed64(12, 76_561_198_000_000_000).unwrap();
        let mut outer = ProtoWriter::new();
        outer.write_message(2, &replacement).unwrap();

        let parsed = parse_replace_continue_response(outer.as_bytes()).unwrap();
        assert_eq!(parsed.serial_number.as_deref(), Some("42"));
        assert_eq!(parsed.revocation_code.as_deref(), Some("R1"));
        assert_eq!(parsed.server_time, Some(1_700_000_000));
        assert_eq!(parsed.steamguard_scheme, Some(2));
        assert_eq!(parsed.steam_id.as_deref(), Some("76561198000000000"));
    }

    #[test]
    fn replace_continue_request_trims_code() {
        let request = build_replace_continue_request(" 12345 ").unwrap();
        let fields = parse_all(&request).unwrap();
        assert_eq!(fields[0].as_utf8_lossy().as_deref(), Some("12345"));
        assert_eq!(fields[1].as_i64(), Some(1));
        assert_eq!(fields[2].as_i64(), Some(2));
    }
}
