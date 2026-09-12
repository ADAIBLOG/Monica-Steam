use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};

const MAX_AUTHORIZED_DEVICE_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const MAX_AUTHORIZED_DEVICES: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthorizedDeviceUsage {
    pub time_seconds: i64,
    pub country: String,
    pub state: String,
    pub city: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthorizedDevice {
    /// `None` means the token field was absent. `Some(0)` deliberately remains
    /// distinct because the Kotlin fallback renders an existing malformed or
    /// short fixed64 field as the unsigned string "0".
    pub token_id: Option<u64>,
    pub description: String,
    pub platform_type: i32,
    pub logged_in: bool,
    pub first_seen: Option<AuthorizedDeviceUsage>,
    pub last_seen: Option<AuthorizedDeviceUsage>,
    pub is_current: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthorizedDeviceParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyDevices,
}

impl From<ProtoError> for AuthorizedDeviceParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_authorized_devices(
    response: &[u8],
) -> Result<Vec<AuthorizedDevice>, AuthorizedDeviceParseError> {
    if response.len() > MAX_AUTHORIZED_DEVICE_RESPONSE_BYTES {
        return Err(AuthorizedDeviceParseError::PayloadTooLarge);
    }

    let fields = parse_all_ref(response)?;
    let requesting_token = fields
        .iter()
        .find(|field| field.number == 2)
        .map(kotlin_fixed64_unsigned);
    let candidate_count = fields
        .iter()
        .filter(|field| field.number == 1 && has_kotlin_bytes(field))
        .count();
    if candidate_count > MAX_AUTHORIZED_DEVICES {
        return Err(AuthorizedDeviceParseError::TooManyDevices);
    }

    let mut devices = Vec::with_capacity(candidate_count);
    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(parsed) = with_kotlin_bytes(field, parse_device) else {
            continue;
        };
        let Some(mut device) = parsed? else {
            continue;
        };
        device.is_current = requesting_token.is_some() && device.token_id == requesting_token;
        devices.push(device);
    }
    Ok(devices)
}

fn parse_device(bytes: &[u8]) -> Result<Option<AuthorizedDevice>, AuthorizedDeviceParseError> {
    let fields = parse_all_ref(bytes)?;
    let token_id = last_field(&fields, 1).map(kotlin_fixed64_unsigned);
    let description = last_string(&fields, 2);
    if token_id.is_none() && description.is_empty() {
        return Ok(None);
    }

    Ok(Some(AuthorizedDevice {
        token_id,
        description,
        platform_type: last_field(&fields, 4).map(kotlin_as_int).unwrap_or(0),
        logged_in: last_field(&fields, 5).map(kotlin_as_bool).unwrap_or(false),
        first_seen: parse_optional_usage(last_field(&fields, 9))?,
        last_seen: parse_optional_usage(last_field(&fields, 10))?,
        is_current: false,
    }))
}

fn parse_optional_usage(
    field: Option<&ProtoFieldRef<'_>>,
) -> Result<Option<AuthorizedDeviceUsage>, AuthorizedDeviceParseError> {
    let Some(field) = field else {
        return Ok(None);
    };
    let Some(parsed) = with_kotlin_bytes(field, parse_usage) else {
        return Ok(None);
    };
    parsed.map(Some)
}

fn parse_usage(bytes: &[u8]) -> Result<AuthorizedDeviceUsage, AuthorizedDeviceParseError> {
    let fields = parse_all_ref(bytes)?;
    Ok(AuthorizedDeviceUsage {
        time_seconds: last_field(&fields, 1).map(kotlin_as_long).unwrap_or(0),
        country: last_string(&fields, 4),
        state: last_string(&fields, 5),
        city: last_string(&fields, 6),
    })
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

fn kotlin_as_long(field: &ProtoFieldRef<'_>) -> i64 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64,
        _ => 0,
    }
}

fn kotlin_as_bool(field: &ProtoFieldRef<'_>) -> bool {
    match field.value {
        ProtoValueRef::Varint(value) => value != 0,
        _ => false,
    }
}

fn kotlin_fixed64_unsigned(field: &ProtoFieldRef<'_>) -> u64 {
    match field.value {
        ProtoValueRef::Fixed64(value) => value,
        ProtoValueRef::Bytes(bytes) if bytes.len() >= 8 => u64::from_le_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        ]),
        _ => 0,
    }
}

fn last_string(fields: &[ProtoFieldRef<'_>], number: u32) -> String {
    last_field(fields, number)
        .and_then(|field| {
            with_kotlin_bytes(field, |bytes| String::from_utf8_lossy(bytes).into_owned())
        })
        .unwrap_or_default()
}

fn has_kotlin_bytes(field: &ProtoFieldRef<'_>) -> bool {
    !matches!(field.value, ProtoValueRef::Varint(_))
}

fn with_kotlin_bytes<T>(field: &ProtoFieldRef<'_>, f: impl FnOnce(&[u8]) -> T) -> Option<T> {
    match field.value {
        ProtoValueRef::Varint(_) => None,
        ProtoValueRef::Bytes(bytes) => Some(f(bytes)),
        ProtoValueRef::Fixed64(value) => {
            let bytes = value.to_le_bytes();
            Some(f(&bytes))
        }
        ProtoValueRef::Fixed32(value) => {
            let bytes = value.to_le_bytes();
            Some(f(&bytes))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    fn usage(time: i64, country: &str, state: &str, city: &str) -> ProtoWriter {
        let mut message = ProtoWriter::new();
        message.write_varint(1, time).unwrap();
        message.write_string(4, country).unwrap();
        message.write_string(5, state).unwrap();
        message.write_string(6, city).unwrap();
        message
    }

    fn device(token_id: i64, description: &str) -> ProtoWriter {
        let mut message = ProtoWriter::new();
        message.write_fixed64(1, token_id).unwrap();
        message.write_string(2, description).unwrap();
        message.write_varint(4, 3).unwrap();
        message.write_bool(5, true).unwrap();
        message.write_message(9, &usage(10, "US", "CA", "San Francisco")).unwrap();
        message.write_message(10, &usage(20, "US", "WA", "Seattle")).unwrap();
        message
    }

    #[test]
    fn parses_nested_devices_and_marks_requesting_token() {
        let mut response = ProtoWriter::new();
        response.write_message(1, &device(-2, "This phone")).unwrap();
        response.write_message(1, &device(42, "Old phone")).unwrap();
        response.write_fixed64(2, -2).unwrap();

        let parsed = parse_authorized_devices(response.as_bytes()).unwrap();
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[0].token_id, Some(u64::MAX - 1));
        assert_eq!(parsed[0].description, "This phone");
        assert!(parsed[0].logged_in);
        assert!(parsed[0].is_current);
        assert_eq!(parsed[0].first_seen.as_ref().unwrap().city, "San Francisco");
        assert_eq!(parsed[0].last_seen.as_ref().unwrap().time_seconds, 20);
        assert!(!parsed[1].is_current);
    }

    #[test]
    fn nested_maps_keep_last_duplicate_fields_while_requesting_token_keeps_first() {
        let mut item = device(7, "old");
        item.write_string(2, "new").unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &item).unwrap();
        response.write_fixed64(2, 7).unwrap();
        response.write_fixed64(2, 8).unwrap();

        let parsed = parse_authorized_devices(response.as_bytes()).unwrap();
        assert_eq!(parsed[0].description, "new");
        assert!(parsed[0].is_current);
    }

    #[test]
    fn empty_nested_device_is_filtered_like_kotlin() {
        let empty = ProtoWriter::new();
        let mut response = ProtoWriter::new();
        response.write_message(1, &empty).unwrap();
        assert!(parse_authorized_devices(response.as_bytes()).unwrap().is_empty());
    }

    #[test]
    fn malformed_nested_device_fails_the_native_parse_for_kotlin_fallback() {
        let mut response = ProtoWriter::new();
        response.write_bytes(1, &[0x08, 0x80]).unwrap();
        assert!(matches!(
            parse_authorized_devices(response.as_bytes()),
            Err(AuthorizedDeviceParseError::Proto(_))
        ));
    }
}
