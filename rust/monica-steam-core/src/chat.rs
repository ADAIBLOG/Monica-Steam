use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};
use std::collections::HashSet;

const STEAM_ID64_INDIVIDUAL_BASE: i64 = 76_561_197_960_265_728;
const MAX_CHAT_RESPONSE_BYTES: usize = 32 * 1024 * 1024;
const MAX_CHAT_ITEMS: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatSession {
    pub partner_steam_id: i64,
    pub last_message_timestamp: i64,
    pub last_view_timestamp: i64,
    pub unread_count: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChatReactionKind {
    Emoticon = 1,
    Sticker = 2,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatReaction {
    pub kind: ChatReactionKind,
    pub name: String,
    pub reactor_steam_ids: Vec<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatMessage {
    pub sender_steam_id: i64,
    pub timestamp: i64,
    pub ordinal: i32,
    pub body: String,
    pub reactions: Vec<ChatReaction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatPage {
    pub messages: Vec<ChatMessage>,
    pub more_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChatParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyItems,
}

impl From<ProtoError> for ChatParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_chat_sessions(response: &[u8]) -> Result<Vec<ChatSession>, ChatParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let mut sessions = Vec::new();
    let mut seen = HashSet::new();

    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(bytes) = field.as_bytes() else {
            continue;
        };
        let Ok(session_fields) = parse_all_ref(bytes) else {
            continue;
        };
        let Some(account_id) = last_varint_i64(&session_fields, 1).filter(|value| *value > 0) else {
            continue;
        };
        let partner_steam_id = steam_id64_from_account_id(account_id);
        if !seen.insert(partner_steam_id) {
            continue;
        }
        if sessions.len() >= MAX_CHAT_ITEMS {
            return Err(ChatParseError::TooManyItems);
        }
        sessions.push(ChatSession {
            partner_steam_id,
            last_message_timestamp: last_varint_i64(&session_fields, 2).unwrap_or(0).max(0),
            last_view_timestamp: last_varint_i64(&session_fields, 3).unwrap_or(0).max(0),
            unread_count: last_varint_i64(&session_fields, 4)
                .unwrap_or(0)
                .clamp(0, i32::MAX as i64) as i32,
        });
    }

    sessions.sort_by(|left, right| {
        right
            .last_message_timestamp
            .cmp(&left.last_message_timestamp)
    });
    Ok(sessions)
}

pub fn parse_chat_messages(response: &[u8]) -> Result<ChatPage, ChatParseError> {
    ensure_payload_size(response)?;
    let fields = parse_all_ref(response)?;
    let more_available = first_varint_i64(&fields, 4).unwrap_or(0) != 0;
    let mut messages = Vec::new();
    let mut seen = HashSet::new();

    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(bytes) = field.as_bytes() else {
            continue;
        };
        let Ok(message_fields) = parse_all_ref(bytes) else {
            continue;
        };
        let Some(account_id) = first_varint_i64(&message_fields, 1).filter(|value| *value > 0) else {
            continue;
        };
        let Some(raw_body) = first_bytes(&message_fields, 3) else {
            continue;
        };
        let body = String::from_utf8_lossy(raw_body).trim_end().to_owned();
        if body.trim().is_empty() {
            continue;
        }

        let sender_steam_id = steam_id64_from_account_id(account_id);
        let timestamp = first_varint_i64(&message_fields, 2).unwrap_or(0).max(0);
        let ordinal = first_varint_i64(&message_fields, 4)
            .unwrap_or(0)
            .clamp(0, i32::MAX as i64) as i32;
        let stable_id = (timestamp, ordinal, sender_steam_id);
        if !seen.insert(stable_id) {
            continue;
        }
        if messages.len() >= MAX_CHAT_ITEMS {
            return Err(ChatParseError::TooManyItems);
        }

        messages.push(ChatMessage {
            sender_steam_id,
            timestamp,
            ordinal,
            body,
            reactions: parse_reactions(&message_fields),
        });
    }

    messages.sort_by(|left, right| {
        left.timestamp
            .cmp(&right.timestamp)
            .then_with(|| left.ordinal.cmp(&right.ordinal))
    });
    Ok(ChatPage {
        messages,
        more_available,
    })
}

fn parse_reactions(message_fields: &[ProtoFieldRef<'_>]) -> Vec<ChatReaction> {
    let mut reactions = Vec::new();
    for field in message_fields.iter().filter(|field| field.number == 5) {
        let Some(bytes) = field.as_bytes() else {
            continue;
        };
        let Ok(fields) = parse_all_ref(bytes) else {
            continue;
        };
        let kind = match first_varint_i64(&fields, 1) {
            Some(1) => ChatReactionKind::Emoticon,
            Some(2) => ChatReactionKind::Sticker,
            _ => continue,
        };
        let Some(name_bytes) = first_bytes(&fields, 2) else {
            continue;
        };
        let name = String::from_utf8_lossy(name_bytes)
            .trim()
            .trim_matches(':')
            .to_owned();
        if name.trim().is_empty() {
            continue;
        }

        let mut reactor_steam_ids = Vec::new();
        let mut seen_reactors = HashSet::new();
        for reactor in fields.iter().filter(|field| field.number == 3) {
            let Some(account_id) = proto_varint_i64(reactor).filter(|value| *value > 0) else {
                continue;
            };
            let steam_id = steam_id64_from_account_id(account_id);
            if seen_reactors.insert(steam_id) {
                reactor_steam_ids.push(steam_id);
            }
        }
        reactions.push(ChatReaction {
            kind,
            name,
            reactor_steam_ids,
        });
    }
    reactions
}

fn ensure_payload_size(response: &[u8]) -> Result<(), ChatParseError> {
    if response.len() > MAX_CHAT_RESPONSE_BYTES {
        Err(ChatParseError::PayloadTooLarge)
    } else {
        Ok(())
    }
}

fn steam_id64_from_account_id(account_id: i64) -> i64 {
    STEAM_ID64_INDIVIDUAL_BASE + (account_id & 0xffff_ffff)
}

fn proto_varint_i64(field: &ProtoFieldRef<'_>) -> Option<i64> {
    match field.value {
        ProtoValueRef::Varint(value) => Some(value as i64),
        _ => None,
    }
}

fn first_varint_i64(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<i64> {
    fields
        .iter()
        .find(|field| field.number == number)
        .and_then(proto_varint_i64)
}

fn last_varint_i64(fields: &[ProtoFieldRef<'_>], number: u32) -> Option<i64> {
    fields
        .iter()
        .rev()
        .find(|field| field.number == number)
        .and_then(proto_varint_i64)
}

fn first_bytes<'a>(fields: &[ProtoFieldRef<'a>], number: u32) -> Option<&'a [u8]> {
    fields
        .iter()
        .find(|field| field.number == number)
        .and_then(ProtoFieldRef::as_bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    fn message(
        sender_account_id: i64,
        timestamp: i64,
        ordinal: i64,
        body: &str,
        reaction_name: Option<&str>,
    ) -> ProtoWriter {
        let mut writer = ProtoWriter::new();
        writer.write_varint(1, sender_account_id).unwrap();
        writer.write_varint(2, timestamp).unwrap();
        writer.write_string(3, body).unwrap();
        writer.write_varint(4, ordinal).unwrap();
        if let Some(name) = reaction_name {
            let mut reaction = ProtoWriter::new();
            reaction.write_varint(1, 1).unwrap();
            reaction.write_string(2, name).unwrap();
            reaction.write_varint(3, 33_734_273).unwrap();
            reaction.write_varint(3, 33_734_273).unwrap();
            writer.write_message(5, &reaction).unwrap();
        }
        writer
    }

    #[test]
    fn sessions_match_kotlin_field_and_sort_semantics() {
        let mut older = ProtoWriter::new();
        older.write_varint(1, 33_734_274).unwrap();
        older.write_varint(2, 100).unwrap();
        older.write_varint(4, 3).unwrap();

        let mut newer = ProtoWriter::new();
        newer.write_varint(1, 33_734_275).unwrap();
        newer.write_varint(2, 200).unwrap();
        newer.write_varint(3, 150).unwrap();
        newer.write_varint(4, i64::MAX).unwrap();

        let mut response = ProtoWriter::new();
        response.write_message(1, &older).unwrap();
        response.write_message(1, &newer).unwrap();
        response.write_message(1, &older).unwrap();

        let sessions = parse_chat_sessions(response.as_bytes()).unwrap();
        assert_eq!(sessions.len(), 2);
        assert_eq!(sessions[0].last_message_timestamp, 200);
        assert_eq!(sessions[0].unread_count, i32::MAX);
        assert_eq!(sessions[1].last_message_timestamp, 100);
        assert_eq!(sessions[1].unread_count, 3);
    }

    #[test]
    fn messages_parse_reactions_dedupe_and_sort_like_kotlin() {
        let mut response = ProtoWriter::new();
        response
            .write_message(1, &message(33_734_274, 200, 2, " second  ", None))
            .unwrap();
        response
            .write_message(
                1,
                &message(33_734_273, 100, 1, "Hello   ", Some(":steamthumbsup:")),
            )
            .unwrap();
        response
            .write_message(1, &message(33_734_273, 100, 1, "duplicate", None))
            .unwrap();
        response.write_varint(4, 1).unwrap();

        let page = parse_chat_messages(response.as_bytes()).unwrap();
        assert!(page.more_available);
        assert_eq!(page.messages.len(), 2);
        assert_eq!(page.messages[0].body, "Hello");
        assert_eq!(page.messages[1].body, " second");
        assert_eq!(page.messages[0].reactions.len(), 1);
        assert_eq!(page.messages[0].reactions[0].name, "steamthumbsup");
        assert_eq!(page.messages[0].reactions[0].reactor_steam_ids.len(), 1);
    }

    #[test]
    fn malformed_nested_entries_are_skipped_without_losing_the_page() {
        let mut response = ProtoWriter::new();
        response.write_bytes(1, &[0x08, 0x80]).unwrap();
        response
            .write_message(1, &message(33_734_273, 10, 1, "ok", None))
            .unwrap();
        let page = parse_chat_messages(response.as_bytes()).unwrap();
        assert_eq!(page.messages.len(), 1);
        assert_eq!(page.messages[0].body, "ok");
    }
}
