use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};

const MAX_AUTH_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
const MAX_AUTH_SESSIONS: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthSessionInfo {
    pub version: i32,
    pub ip: String,
    pub city: String,
    pub country: String,
    pub device_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthSessionParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManySessions,
}

impl From<ProtoError> for AuthSessionParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_pending_login_client_ids(
    response: &[u8],
) -> Result<Vec<i64>, AuthSessionParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let mut ids = Vec::new();

    for field in fields.iter().filter(|field| field.number == 1) {
        match field.value {
            ProtoValueRef::Varint(value) => push_id(&mut ids, value as i64)?,
            ProtoValueRef::Bytes(bytes) => {
                for value in decode_kotlin_packed_varints(bytes) {
                    push_id(&mut ids, value)?;
                }
            }
            ProtoValueRef::Fixed64(value) => {
                for value in decode_kotlin_packed_varints(&value.to_le_bytes()) {
                    push_id(&mut ids, value)?;
                }
            }
            ProtoValueRef::Fixed32(value) => {
                for value in decode_kotlin_packed_varints(&value.to_le_bytes()) {
                    push_id(&mut ids, value)?;
                }
            }
        }
    }

    Ok(ids)
}

pub fn parse_auth_session_info(response: &[u8]) -> Result<AuthSessionInfo, AuthSessionParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    Ok(AuthSessionInfo {
        version: last_field(&fields, 8).map(kotlin_as_int).unwrap_or(0),
        ip: last_string(&fields, 1),
        city: last_string(&fields, 3),
        country: last_string(&fields, 5),
        device_name: last_string(&fields, 7),
    })
}

pub fn parse_mobile_confirmation_success(
    response: &[u8],
) -> Result<bool, AuthSessionParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    if fields.is_empty() {
        return Ok(true);
    }
    Ok(last_field(&fields, 1)
        .map(kotlin_as_bool)
        .unwrap_or(true))
}

fn ensure_payload_size(response: &[u8]) -> Result<(), AuthSessionParseError> {
    if response.len() > MAX_AUTH_RESPONSE_BYTES {
        Err(AuthSessionParseError::PayloadTooLarge)
    } else {
        Ok(())
    }
}

fn push_id(ids: &mut Vec<i64>, value: i64) -> Result<(), AuthSessionParseError> {
    if ids.len() >= MAX_AUTH_SESSIONS {
        return Err(AuthSessionParseError::TooManySessions);
    }
    ids.push(value);
    Ok(())
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

fn kotlin_as_bool(field: &ProtoFieldRef<'_>) -> bool {
    match field.value {
        ProtoValueRef::Varint(value) => value != 0,
        _ => false,
    }
}

fn last_string(fields: &[ProtoFieldRef<'_>], number: u32) -> String {
    last_field(fields, number)
        .and_then(|field| match field.value {
            ProtoValueRef::Varint(_) => None,
            ProtoValueRef::Bytes(bytes) => Some(String::from_utf8_lossy(bytes).into_owned()),
            ProtoValueRef::Fixed64(value) => {
                Some(String::from_utf8_lossy(&value.to_le_bytes()).into_owned())
            }
            ProtoValueRef::Fixed32(value) => {
                Some(String::from_utf8_lossy(&value.to_le_bytes()).into_owned())
            }
        })
        .unwrap_or_default()
}

// SteamProtoReader.decodePackedVarints() accepts the same 64-bit varint shapes
// as the regular reader. Keep the behavior local so auth-session parsing remains
// one native operation instead of bouncing packed fields back through Kotlin.
fn decode_kotlin_packed_varints(bytes: &[u8]) -> Vec<i64> {
    let mut values = Vec::new();
    let mut pos = 0usize;
    while pos < bytes.len() {
        let mut result = 0u64;
        let mut completed = false;
        for index in 0..10 {
            let Some(byte) = bytes.get(pos).copied() else {
                return values;
            };
            pos += 1;
            if index == 9 && byte > 1 {
                return values;
            }
            result |= ((byte & 0x7f) as u64) << (index * 7);
            if byte & 0x80 == 0 {
                completed = true;
                break;
            }
        }
        if !completed {
            return values;
        }
        values.push(result as i64);
    }
    values
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    #[test]
    fn pending_client_ids_keep_source_order_and_packed_values() {
        let mut packed = Vec::new();
        packed.extend_from_slice(&[0x96, 0x01]); // 150
        packed.extend_from_slice(&[0x2a]); // 42
        let mut response = ProtoWriter::new();
        response.write_varint(1, 7).unwrap();
        response.write_bytes(1, &packed).unwrap();

        assert_eq!(
            parse_pending_login_client_ids(response.as_bytes()).unwrap(),
            vec![7, 150, 42]
        );
    }

    #[test]
    fn session_info_uses_last_duplicate_fields_like_kotlin_map() {
        let mut response = ProtoWriter::new();
        response.write_string(1, "old-ip").unwrap();
        response.write_string(1, "new-ip").unwrap();
        response.write_string(3, "City").unwrap();
        response.write_string(5, "Country").unwrap();
        response.write_string(7, "Phone").unwrap();
        response.write_varint(8, 1).unwrap();
        response.write_varint(8, 2).unwrap();

        let info = parse_auth_session_info(response.as_bytes()).unwrap();
        assert_eq!(info.version, 2);
        assert_eq!(info.ip, "new-ip");
        assert_eq!(info.city, "City");
        assert_eq!(info.country, "Country");
        assert_eq!(info.device_name, "Phone");
    }

    #[test]
    fn confirmation_success_matches_empty_and_boolean_fallback_rules() {
        assert!(parse_mobile_confirmation_success(&[]).unwrap());

        let mut success = ProtoWriter::new();
        success.write_bool(1, true).unwrap();
        assert!(parse_mobile_confirmation_success(success.as_bytes()).unwrap());

        let mut failure = ProtoWriter::new();
        failure.write_bool(1, false).unwrap();
        assert!(!parse_mobile_confirmation_success(failure.as_bytes()).unwrap());
    }
}
