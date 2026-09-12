use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};
use std::collections::HashMap;

const INDIVIDUAL_STEAM_ID_BASE: i64 = 76_561_197_960_265_728;
const MAX_NICKNAME_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const MAX_NICKNAMES: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FriendNickname {
    pub steam_id: i64,
    pub nickname: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FriendNicknameParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyNicknames,
}

impl From<ProtoError> for FriendNicknameParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_friend_nicknames(
    response: &[u8],
) -> Result<Vec<FriendNickname>, FriendNicknameParseError> {
    if response.len() > MAX_NICKNAME_RESPONSE_BYTES {
        return Err(FriendNicknameParseError::PayloadTooLarge);
    }

    let fields = parse_all_ref(response)?;
    let candidate_count = fields
        .iter()
        .filter(|field| field.number == 1 && has_kotlin_bytes(field))
        .count();
    if candidate_count > MAX_NICKNAMES {
        return Err(FriendNicknameParseError::TooManyNicknames);
    }

    // Kotlin uses a LinkedHashMap. Replacing a duplicate key updates the value
    // while preserving the first insertion position, so keep an index table and
    // update the existing vector slot in place.
    let mut output = Vec::<FriendNickname>::with_capacity(candidate_count);
    let mut indices = HashMap::<i64, usize>::with_capacity(candidate_count);

    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(parsed) = with_kotlin_bytes(field, |bytes| {
            let nested = parse_all_ref(bytes)?;
            let account_id = last_field(&nested, 1)
                .map(kotlin_fixed32_unsigned_long)
                .unwrap_or(0);
            let nickname = last_field(&nested, 2)
                .and_then(|field| {
                    with_kotlin_bytes(field, |bytes| {
                        String::from_utf8_lossy(bytes).trim().to_owned()
                    })
                })
                .unwrap_or_default();
            Ok::<_, ProtoError>((account_id, nickname))
        }) else {
            continue;
        };
        let (account_id, nickname) = parsed?;
        if account_id == 0 || nickname.trim().is_empty() {
            continue;
        }

        let steam_id = INDIVIDUAL_STEAM_ID_BASE + account_id as i64;
        let value = FriendNickname { steam_id, nickname };
        if let Some(index) = indices.get(&steam_id).copied() {
            output[index] = value;
        } else {
            indices.insert(steam_id, output.len());
            output.push(value);
        }
    }

    Ok(output)
}

fn last_field<'fields, 'data>(
    fields: &'fields [ProtoFieldRef<'data>],
    number: u32,
) -> Option<&'fields ProtoFieldRef<'data>> {
    fields.iter().rev().find(|field| field.number == number)
}

fn kotlin_fixed32_unsigned_long(field: &ProtoFieldRef<'_>) -> u32 {
    match field.value {
        ProtoValueRef::Varint(_) => 0,
        ProtoValueRef::Fixed32(value) => value,
        ProtoValueRef::Fixed64(value) => value as u32,
        ProtoValueRef::Bytes(bytes) => {
            if bytes.len() < 4 {
                0
            } else {
                u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
            }
        }
    }
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

    fn nickname(account_id: u32, value: &str) -> ProtoWriter {
        let mut item = ProtoWriter::new();
        item.write_fixed32(1, account_id).unwrap();
        item.write_string(2, value).unwrap();
        item
    }

    #[test]
    fn parses_nicknames_and_preserves_linked_map_duplicate_semantics() {
        let mut first = nickname(39_734_274, "  Alice  ");
        first.write_string(2, "Alice final").unwrap();
        let second = nickname(39_734_275, "Bob");
        let replacement = nickname(39_734_274, "Alice newest");

        let mut response = ProtoWriter::new();
        response.write_message(1, &first).unwrap();
        response.write_message(1, &second).unwrap();
        response.write_message(1, &replacement).unwrap();

        let parsed = parse_friend_nicknames(response.as_bytes()).unwrap();
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[0].steam_id, 76_561_198_000_000_002);
        assert_eq!(parsed[0].nickname, "Alice newest");
        assert_eq!(parsed[1].steam_id, 76_561_198_000_000_003);
        assert_eq!(parsed[1].nickname, "Bob");
    }

    #[test]
    fn short_or_wrong_wire_account_ids_are_ignored_like_kotlin() {
        let mut short = ProtoWriter::new();
        short.write_bytes(1, &[1, 2, 3]).unwrap();
        short.write_string(2, "short").unwrap();
        let mut varint = ProtoWriter::new();
        varint.write_varint(1, 39_734_274).unwrap();
        varint.write_string(2, "wrong wire").unwrap();
        let valid = nickname(39_734_275, "valid");

        let mut response = ProtoWriter::new();
        response.write_message(1, &short).unwrap();
        response.write_message(1, &varint).unwrap();
        response.write_message(1, &valid).unwrap();

        let parsed = parse_friend_nicknames(response.as_bytes()).unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].nickname, "valid");
    }

    #[test]
    fn malformed_nested_item_propagates_like_kotlin_parser() {
        let mut response = ProtoWriter::new();
        response.write_bytes(1, &[0x08, 0x80]).unwrap();
        assert!(matches!(
            parse_friend_nicknames(response.as_bytes()),
            Err(FriendNicknameParseError::Proto(_))
        ));
    }
}
