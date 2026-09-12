use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};
use std::collections::HashSet;

const MAX_GROUP_CHAT_RESPONSE_BYTES: usize = 32 * 1024 * 1024;
const MAX_GROUP_CHAT_MESSAGES: usize = 100_000;
const MAX_REACTIONS_PER_MESSAGE: usize = 4_096;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupChatReaction {
    pub kind: i32,
    pub name: String,
    pub count: i32,
    pub has_user_reacted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupChatHistoryMessage {
    pub sender_account_id: i64,
    pub timestamp: i64,
    pub ordinal: i32,
    pub raw_body: String,
    pub deleted: bool,
    pub event_type: i32,
    pub event_text: String,
    pub reactions: Vec<GroupChatReaction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupChatHistoryPage {
    pub messages: Vec<GroupChatHistoryMessage>,
    pub more_available: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GroupChatParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyMessages,
    TooManyReactions,
}

impl From<ProtoError> for GroupChatParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_group_chat_history(
    response: &[u8],
) -> Result<GroupChatHistoryPage, GroupChatParseError> {
    if response.len() > MAX_GROUP_CHAT_RESPONSE_BYTES {
        return Err(GroupChatParseError::PayloadTooLarge);
    }

    let fields = parse_all_ref(response)?;
    let more_available = first_varint_i64(&fields, 4).unwrap_or(0) != 0;
    let candidate_count = fields
        .iter()
        .filter(|field| field.number == 1 && field.as_bytes().is_some())
        .count();
    if candidate_count > MAX_GROUP_CHAT_MESSAGES {
        return Err(GroupChatParseError::TooManyMessages);
    }

    let mut messages = Vec::with_capacity(candidate_count);
    let mut seen = HashSet::with_capacity(candidate_count);

    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(bytes) = field.as_bytes() else {
            continue;
        };
        // Kotlin parseHistory() does not catch malformed nested messages, server
        // events, or reactions. Propagate those errors before stable-id dedupe so
        // duplicate rows preserve the same failure semantics.
        let message_fields = parse_all_ref(bytes)?;
        let sender_account_id = last_varint_i64(&message_fields, 1)
            .filter(|value| *value > 0)
            .unwrap_or(0);
        let timestamp = last_varint_i64(&message_fields, 2).unwrap_or(0).max(0);
        let ordinal = last_varint_i64(&message_fields, 4).unwrap_or(0) as i32;
        let raw_body = last_bytes(&message_fields, 3)
            .map(|value| String::from_utf8_lossy(value).into_owned())
            .unwrap_or_default();
        let deleted = last_varint_i64(&message_fields, 6).unwrap_or(0) != 0;
        let (event_type, event_text) = match last_bytes(&message_fields, 5) {
            Some(event) => parse_server_event(event)?,
            None => (0, String::new()),
        };
        let reactions = parse_reactions(&message_fields)?;

        // Kotlin fills a blank body from steamGroupEventText() only for positive
        // event types. Keep those rows raw so localization remains on the JVM.
        if raw_body.trim().is_empty() && event_type <= 0 {
            continue;
        }

        let stable_sender = if sender_account_id > 0 {
            sender_account_id & 0xffff_ffff
        } else {
            0
        };
        if !seen.insert((timestamp, ordinal, stable_sender)) {
            continue;
        }
        if messages.len() >= MAX_GROUP_CHAT_MESSAGES {
            return Err(GroupChatParseError::TooManyMessages);
        }

        messages.push(GroupChatHistoryMessage {
            sender_account_id,
            timestamp,
            ordinal,
            raw_body,
            deleted,
            event_type,
            event_text,
            reactions,
        });
    }

    messages.sort_by(|left, right| {
        left.timestamp
            .cmp(&right.timestamp)
            .then_with(|| left.ordinal.cmp(&right.ordinal))
    });

    Ok(GroupChatHistoryPage {
        messages,
        more_available,
    })
}

fn parse_server_event(payload: &[u8]) -> Result<(i32, String), GroupChatParseError> {
    let fields = parse_all_ref(payload)?;
    let event_type = last_varint_i64(&fields, 1).unwrap_or(0) as i32;
    let event_text = last_bytes(&fields, 2)
        .map(|value| String::from_utf8_lossy(value).into_owned())
        .unwrap_or_default();
    Ok((event_type, event_text))
}

fn parse_reactions(
    message_fields: &[ProtoFieldRef<'_>],
) -> Result<Vec<GroupChatReaction>, GroupChatParseError> {
    let mut reactions = Vec::new();
    for field in message_fields.iter().filter(|field| field.number == 7) {
        let Some(bytes) = field.as_bytes() else {
            continue;
        };
        let fields = parse_all_ref(bytes)?;
        let Some(name_bytes) = last_bytes(&fields, 2) else {
            continue;
        };
        let name = String::from_utf8_lossy(name_bytes).into_owned();
        if name.trim().is_empty() {
            continue;
        }
        if reactions.len() >= MAX_REACTIONS_PER_MESSAGE {
            return Err(GroupChatParseError::TooManyReactions);
        }

        let raw_kind = last_varint_i64(&fields, 1).unwrap_or(0) as i32;
        let raw_count = last_varint_i64(&fields, 3).unwrap_or(0) as i32;
        reactions.push(GroupChatReaction {
            kind: if raw_kind == 2 { 2 } else { 1 },
            name,
            count: raw_count.max(0),
            has_user_reacted: last_varint_i64(&fields, 4).unwrap_or(0) != 0,
        });
    }
    Ok(reactions)
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

fn last_bytes<'a>(fields: &[ProtoFieldRef<'a>], number: u32) -> Option<&'a [u8]> {
    fields
        .iter()
        .rev()
        .find(|field| field.number == number)
        .and_then(ProtoFieldRef::as_bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ProtoWriter;

    fn reaction(kind: i64, name: &str, count: i64, has_user_reacted: bool) -> ProtoWriter {
        let mut writer = ProtoWriter::new();
        writer.write_varint(1, kind).unwrap();
        writer.write_string(2, name).unwrap();
        writer.write_varint(3, count).unwrap();
        writer
            .write_varint(4, if has_user_reacted { 1 } else { 0 })
            .unwrap();
        writer
    }

    fn message(
        sender: i64,
        timestamp: i64,
        ordinal: i64,
        body: Option<&str>,
        reaction_value: Option<ProtoWriter>,
    ) -> ProtoWriter {
        let mut writer = ProtoWriter::new();
        if sender != 0 {
            writer.write_varint(1, sender).unwrap();
        }
        writer.write_varint(2, timestamp).unwrap();
        if let Some(body) = body {
            writer.write_string(3, body).unwrap();
        }
        writer.write_varint(4, ordinal).unwrap();
        if let Some(reaction_value) = reaction_value {
            writer.write_message(7, &reaction_value).unwrap();
        }
        writer
    }

    #[test]
    fn parses_reactions_dedupes_and_sorts_like_kotlin() {
        let mut response = ProtoWriter::new();
        response
            .write_message(1, &message(39_734_275, 200, 2, Some("second"), None))
            .unwrap();
        response
            .write_message(
                1,
                &message(
                    39_734_274,
                    100,
                    1,
                    Some("Hello"),
                    Some(reaction(2, "party", 2, true)),
                ),
            )
            .unwrap();
        response
            .write_message(1, &message(39_734_274, 100, 1, Some("duplicate"), None))
            .unwrap();
        response.write_varint(4, 1).unwrap();

        let page = parse_group_chat_history(response.as_bytes()).unwrap();
        assert!(page.more_available);
        assert_eq!(page.messages.len(), 2);
        assert_eq!(page.messages[0].raw_body, "Hello");
        assert_eq!(page.messages[0].reactions.len(), 1);
        assert_eq!(page.messages[0].reactions[0].kind, 2);
        assert_eq!(page.messages[0].reactions[0].count, 2);
        assert!(page.messages[0].reactions[0].has_user_reacted);
        assert_eq!(page.messages[1].raw_body, "second");
    }

    #[test]
    fn keeps_blank_event_rows_for_kotlin_localization() {
        let mut event = ProtoWriter::new();
        event.write_varint(1, 5).unwrap();
        event.write_string(2, "A member").unwrap();
        let mut system = message(0, 301, 3, None, None);
        system.write_message(5, &event).unwrap();

        let mut response = ProtoWriter::new();
        response.write_message(1, &system).unwrap();
        let page = parse_group_chat_history(response.as_bytes()).unwrap();

        assert_eq!(page.messages.len(), 1);
        assert_eq!(page.messages[0].event_type, 5);
        assert_eq!(page.messages[0].event_text, "A member");
        assert!(page.messages[0].raw_body.is_empty());
    }

    #[test]
    fn last_duplicate_message_fields_win_like_associate_by() {
        let mut row = ProtoWriter::new();
        row.write_varint(1, 1).unwrap();
        row.write_varint(1, 2).unwrap();
        row.write_varint(2, 10).unwrap();
        row.write_varint(2, 20).unwrap();
        row.write_string(3, "old").unwrap();
        row.write_string(3, "new").unwrap();
        row.write_varint(4, 7).unwrap();
        row.write_varint(4, i64::MAX).unwrap();

        let mut response = ProtoWriter::new();
        response.write_message(1, &row).unwrap();
        let page = parse_group_chat_history(response.as_bytes()).unwrap();
        let item = &page.messages[0];

        assert_eq!(item.sender_account_id, 2);
        assert_eq!(item.timestamp, 20);
        assert_eq!(item.raw_body, "new");
        assert_eq!(item.ordinal, -1);
    }

    #[test]
    fn malformed_nested_reaction_fails_before_duplicate_is_discarded() {
        let first = message(1, 10, 1, Some("ok"), None);
        let mut duplicate = message(1, 10, 1, Some("duplicate"), None);
        duplicate.write_bytes(7, &[0x08, 0x80]).unwrap();

        let mut response = ProtoWriter::new();
        response.write_message(1, &first).unwrap();
        response.write_message(1, &duplicate).unwrap();

        assert!(parse_group_chat_history(response.as_bytes()).is_err());
    }

    #[test]
    fn blank_non_event_rows_are_skipped() {
        let mut response = ProtoWriter::new();
        response
            .write_message(1, &message(1, 10, 1, Some("   "), None))
            .unwrap();
        let page = parse_group_chat_history(response.as_bytes()).unwrap();
        assert!(page.messages.is_empty());
    }
}
