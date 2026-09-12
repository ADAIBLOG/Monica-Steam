use crate::proto::{parse_all_ref, ProtoError, ProtoFieldRef, ProtoValueRef};
use std::collections::{HashMap, HashSet};

const MAX_GROUPS_RESPONSE_BYTES: usize = 32 * 1024 * 1024;
const MAX_GROUPS: usize = 10_000;
const MAX_ROOMS_PER_GROUP: usize = 10_000;
const MAX_MEMBERS_PER_ROOM: usize = 100_000;
const MAX_TOP_MEMBERS_PER_GROUP: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupRoomSummary {
    pub chat_id: u64,
    pub name: String,
    pub sort_order: i32,
    pub last_message_timestamp: i64,
    pub last_message: String,
    pub last_sender_account_id: i64,
    pub last_acknowledged_timestamp: i64,
    pub voice_allowed: bool,
    pub voice_member_account_ids: Vec<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupSummary {
    pub group_id: u64,
    pub name: String,
    pub tagline: String,
    pub owner_account_id: i64,
    pub active_member_count: i32,
    pub active_voice_member_count: i32,
    pub default_chat_id: u64,
    pub rooms: Vec<GroupRoomSummary>,
    pub rank: i32,
    pub avatar_ugc_raw: Vec<u8>,
    pub avatar_legacy_raw: Vec<u8>,
    pub top_member_account_ids: Vec<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GroupSummaryParseError {
    Proto(ProtoError),
    PayloadTooLarge,
    TooManyGroups,
    TooManyRooms,
    TooManyMembers,
}

impl From<ProtoError> for GroupSummaryParseError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn parse_group_summaries(response: &[u8]) -> Result<Vec<GroupSummary>, GroupSummaryParseError> {
    if response.len() > MAX_GROUPS_RESPONSE_BYTES {
        return Err(GroupSummaryParseError::PayloadTooLarge);
    }
    let fields = parse_all_ref(response)?;
    let candidate_count = fields
        .iter()
        .filter(|field| field.number == 1 && has_kotlin_bytes(field))
        .count();
    if candidate_count > MAX_GROUPS {
        return Err(GroupSummaryParseError::TooManyGroups);
    }

    let mut groups = Vec::with_capacity(candidate_count);
    for field in fields.iter().filter(|field| field.number == 1) {
        let Some(parsed) = with_kotlin_bytes(field, parse_summary_pair) else {
            continue;
        };
        if let Some(group) = parsed? {
            groups.push(group);
        }
    }

    // Kotlin sortedByDescending is stable. Rust slice::sort_by is stable too,
    // preserving source order for groups with equal recent timestamps.
    groups.sort_by(|left, right| {
        most_recent_room_timestamp(right).cmp(&most_recent_room_timestamp(left))
    });
    Ok(groups)
}

fn parse_summary_pair(payload: &[u8]) -> Result<Option<GroupSummary>, GroupSummaryParseError> {
    let pair = parse_all_ref(payload)?;
    let summary_field = last_field(&pair, 2);

    // Kotlin parses pair[1].bytes into userState immediately, but delays parsing
    // acknowledgement submessages until after summary/groupId validation. Keep the
    // borrowed userState inside this closure so fixed32/fixed64 temporary bytes
    // never escape while preserving that exact failure order.
    match last_field(&pair, 1) {
        Some(user_state_field) => match with_kotlin_bytes(user_state_field, |bytes| {
            let user_state = parse_all_ref(bytes)?;
            parse_summary_field(summary_field, &user_state)
        }) {
            Some(parsed) => parsed,
            None => parse_summary_field(summary_field, &[]),
        },
        None => parse_summary_field(summary_field, &[]),
    }
}

fn parse_summary_field(
    summary_field: Option<&ProtoFieldRef<'_>>,
    user_state: &[ProtoFieldRef<'_>],
) -> Result<Option<GroupSummary>, GroupSummaryParseError> {
    let Some(summary_field) = summary_field else {
        return Ok(None);
    };
    let Some(parsed) = with_kotlin_bytes(summary_field, |bytes| {
        let summary = parse_all_ref(bytes)?;
        parse_summary_fields(&summary, user_state)
    }) else {
        return Ok(None);
    };
    parsed
}

fn parse_summary_fields(
    summary: &[ProtoFieldRef<'_>],
    user_state: &[ProtoFieldRef<'_>],
) -> Result<Option<GroupSummary>, GroupSummaryParseError> {
    // asUnsignedVarintString() is blank only when the field is absent. A present
    // non-varint field becomes numeric zero and therefore the valid string "0".
    let Some(group_id_field) = last_field(summary, 1) else {
        return Ok(None);
    };
    let group_id = kotlin_as_long(group_id_field) as u64;

    // Kotlin does not parse acknowledgement children until groupId is accepted.
    let acknowledgements = parse_acknowledgements(user_state)?;

    let room_count = summary
        .iter()
        .filter(|field| field.number == 6 && has_kotlin_bytes(field))
        .count();
    if room_count > MAX_ROOMS_PER_GROUP {
        return Err(GroupSummaryParseError::TooManyRooms);
    }
    let mut rooms = Vec::with_capacity(room_count);
    for room_field in summary.iter().filter(|field| field.number == 6) {
        let Some(parsed) = with_kotlin_bytes(room_field, |bytes| {
            parse_room(bytes, &acknowledgements)
        }) else {
            continue;
        };
        if let Some(room) = parsed? {
            rooms.push(room);
        }
    }
    rooms.sort_by_key(|room| room.sort_order);

    let default_chat_id = if let Some(field) = last_field(summary, 5) {
        // A present wrong-wire field becomes "0" in Kotlin and wins over fallback.
        kotlin_as_long(field) as u64
    } else if let Some(room) = rooms.first() {
        room.chat_id
    } else {
        return Ok(None);
    };

    let top_member_account_ids = collect_member_ids(
        summary,
        10,
        MAX_TOP_MEMBERS_PER_GROUP,
    )?;

    Ok(Some(GroupSummary {
        group_id,
        name: last_kotlin_string(summary, 2),
        tagline: last_kotlin_string(summary, 8),
        owner_account_id: last_field(summary, 9).map(kotlin_as_long).unwrap_or(0),
        active_member_count: last_field(summary, 3)
            .map(kotlin_as_long)
            .unwrap_or(0) as i32,
        active_voice_member_count: last_field(summary, 4)
            .map(kotlin_as_long)
            .unwrap_or(0) as i32,
        default_chat_id,
        rooms,
        rank: last_field(summary, 12)
            .map(kotlin_as_long)
            .unwrap_or(0) as i32,
        avatar_ugc_raw: last_kotlin_bytes_owned(summary, 21),
        avatar_legacy_raw: last_kotlin_bytes_owned(summary, 11),
        top_member_account_ids,
    }))
}

fn parse_acknowledgements(
    user_state: &[ProtoFieldRef<'_>],
) -> Result<HashMap<u64, i64>, GroupSummaryParseError> {
    let mut acknowledgements = HashMap::new();
    for room_state in user_state.iter().filter(|field| field.number == 3) {
        let Some(parsed) = with_kotlin_bytes(room_state, |bytes| {
            let fields = parse_all_ref(bytes)?;
            let Some(chat_id_field) = last_field(&fields, 1) else {
                return Ok::<_, ProtoError>(None);
            };
            let chat_id = kotlin_as_long(chat_id_field) as u64;
            let timestamp = last_field(&fields, 3)
                .map(kotlin_as_long)
                .unwrap_or(0)
                .max(0);
            Ok(Some((chat_id, timestamp)))
        }) else {
            continue;
        };
        let Some((chat_id, timestamp)) = parsed? else {
            continue;
        };
        // Kotlin toMap() keeps the value from the last duplicate key.
        acknowledgements.insert(chat_id, timestamp);
    }
    Ok(acknowledgements)
}

fn parse_room(
    payload: &[u8],
    acknowledgements: &HashMap<u64, i64>,
) -> Result<Option<GroupRoomSummary>, GroupSummaryParseError> {
    let fields = parse_all_ref(payload)?;
    let Some(chat_id_field) = last_field(&fields, 1) else {
        return Ok(None);
    };
    let chat_id = kotlin_as_long(chat_id_field) as u64;
    let last_message_timestamp = last_field(&fields, 5)
        .map(kotlin_as_long)
        .unwrap_or(0)
        .max(0);
    let last_acknowledged_timestamp = acknowledgements.get(&chat_id).copied().unwrap_or(0);
    let voice_member_account_ids = collect_member_ids(
        &fields,
        4,
        MAX_MEMBERS_PER_ROOM,
    )?;

    Ok(Some(GroupRoomSummary {
        chat_id,
        name: last_kotlin_string(&fields, 2),
        sort_order: last_field(&fields, 6)
            .map(kotlin_as_long)
            .unwrap_or(0) as i32,
        last_message_timestamp,
        last_message: last_kotlin_string(&fields, 7),
        last_sender_account_id: last_field(&fields, 8)
            .map(kotlin_as_long)
            .unwrap_or(0),
        last_acknowledged_timestamp,
        voice_allowed: last_field(&fields, 3)
            .map(kotlin_as_long)
            .unwrap_or(0) != 0,
        voice_member_account_ids,
    }))
}

fn collect_member_ids(
    fields: &[ProtoFieldRef<'_>],
    number: u32,
    limit: usize,
) -> Result<Vec<i64>, GroupSummaryParseError> {
    let mut values = Vec::new();
    let mut seen = HashSet::new();
    for field in fields.iter().filter(|field| field.number == number) {
        match field.value {
            ProtoValueRef::Varint(value) => {
                push_member(value as i64, limit, &mut seen, &mut values)?;
            }
            ProtoValueRef::Bytes(bytes) => {
                for_each_kotlin_packed_varint(bytes, |value| {
                    push_member(value, limit, &mut seen, &mut values)
                })?;
            }
            // Kotlin parseTopMembers/parseRoom explicitly switch on wireType and
            // ignore fixed32/fixed64 for member lists even though `.bytes` exists.
            ProtoValueRef::Fixed64(_) | ProtoValueRef::Fixed32(_) => {}
        }
    }
    Ok(values)
}

fn push_member(
    value: i64,
    limit: usize,
    seen: &mut HashSet<i64>,
    output: &mut Vec<i64>,
) -> Result<(), GroupSummaryParseError> {
    if value <= 0 || !seen.insert(value) {
        return Ok(());
    }
    if output.len() >= limit {
        return Err(GroupSummaryParseError::TooManyMembers);
    }
    output.push(value);
    Ok(())
}

fn for_each_kotlin_packed_varint(
    bytes: &[u8],
    mut consumer: impl FnMut(i64) -> Result<(), GroupSummaryParseError>,
) -> Result<(), GroupSummaryParseError> {
    let mut index = 0usize;
    while index < bytes.len() {
        let mut value = 0i64;
        let mut shift = 0u32;
        while index < bytes.len() && shift < 64 {
            let byte = bytes[index];
            index += 1;
            value |= ((byte & 0x7f) as i64) << shift;
            if byte & 0x80 == 0 {
                break;
            }
            shift += 7;
        }
        // Deliberately append partial/truncated values. The Kotlin helper is lax
        // here and does not throw when a packed varint ends mid-sequence.
        consumer(value)?;
    }
    Ok(())
}

fn most_recent_room_timestamp(group: &GroupSummary) -> i64 {
    group
        .rooms
        .iter()
        .map(|room| room.last_message_timestamp)
        .max()
        .unwrap_or(0)
}

fn last_field<'fields, 'data>(
    fields: &'fields [ProtoFieldRef<'data>],
    number: u32,
) -> Option<&'fields ProtoFieldRef<'data>> {
    fields.iter().rev().find(|field| field.number == number)
}

fn kotlin_as_long(field: &ProtoFieldRef<'_>) -> i64 {
    match field.value {
        ProtoValueRef::Varint(value) => value as i64,
        _ => 0,
    }
}

fn last_kotlin_string(fields: &[ProtoFieldRef<'_>], number: u32) -> String {
    last_field(fields, number)
        .and_then(|field| {
            with_kotlin_bytes(field, |bytes| String::from_utf8_lossy(bytes).into_owned())
        })
        .unwrap_or_default()
}

fn last_kotlin_bytes_owned(fields: &[ProtoFieldRef<'_>], number: u32) -> Vec<u8> {
    last_field(fields, number)
        .and_then(|field| with_kotlin_bytes(field, |bytes| bytes.to_vec()))
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

    const ACCOUNT_A: i64 = 39_734_274;
    const ACCOUNT_B: i64 = 39_734_275;

    fn room(
        chat_id: i64,
        name: &str,
        sort_order: i64,
        last_timestamp: i64,
        voice_allowed: bool,
    ) -> ProtoWriter {
        let mut room = ProtoWriter::new();
        room.write_varint(1, chat_id).unwrap();
        room.write_string(2, name).unwrap();
        room.write_bool(3, voice_allowed).unwrap();
        room.write_varint(5, last_timestamp).unwrap();
        room.write_varint(6, sort_order).unwrap();
        room
    }

    #[test]
    fn parses_rooms_acknowledgements_members_and_stable_sorting() {
        let mut voice = room(9002, "Voice", 2, 100, true);
        voice.write_varint(4, ACCOUNT_A).unwrap();
        voice.write_packed_varints(4, [ACCOUNT_A, ACCOUNT_B]).unwrap();
        voice.write_string(7, "Voice latest").unwrap();
        voice.write_varint(8, ACCOUNT_A).unwrap();
        let text = room(9001, "General", 1, 200, false);

        let mut room_user_state = ProtoWriter::new();
        room_user_state.write_varint(1, 9001).unwrap();
        room_user_state.write_varint(3, 150).unwrap();
        let mut user_state = ProtoWriter::new();
        user_state.write_message(3, &room_user_state).unwrap();

        let mut summary = ProtoWriter::new();
        summary.write_varint(1, 8001).unwrap();
        summary.write_string(2, "Monica testers").unwrap();
        summary.write_varint(3, 12).unwrap();
        summary.write_varint(4, 1).unwrap();
        summary.write_varint(5, 9002).unwrap();
        summary.write_message(6, &voice).unwrap();
        summary.write_message(6, &text).unwrap();
        summary.write_string(8, "Play together").unwrap();
        summary.write_varint(9, ACCOUNT_A).unwrap();
        summary.write_varint(10, ACCOUNT_A).unwrap();
        summary.write_packed_varints(10, [ACCOUNT_A, ACCOUNT_B]).unwrap();
        summary.write_varint(12, 50).unwrap();

        let mut pair = ProtoWriter::new();
        pair.write_message(1, &user_state).unwrap();
        pair.write_message(2, &summary).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        let group = parse_group_summaries(response.as_bytes()).unwrap().remove(0);
        assert_eq!(group.group_id, 8001);
        assert_eq!(group.default_chat_id, 9002);
        assert_eq!(
            group.rooms.iter().map(|room| room.chat_id).collect::<Vec<_>>(),
            vec![9001, 9002]
        );
        assert_eq!(group.rooms[0].last_acknowledged_timestamp, 150);
        assert_eq!(group.rooms[1].voice_member_account_ids, vec![ACCOUNT_A, ACCOUNT_B]);
        assert_eq!(group.top_member_account_ids, vec![ACCOUNT_A, ACCOUNT_B]);
        assert_eq!(group.active_voice_member_count, 1);
    }

    #[test]
    fn duplicate_fields_use_last_values_and_missing_default_uses_first_sorted_room() {
        let mut later_room = room(9002, "later sort", 2, 50, false);
        later_room.write_varint(1, 9999).unwrap();
        let first_room = room(9001, "first sort", 1, 60, false);
        let mut summary = ProtoWriter::new();
        summary.write_varint(1, 7).unwrap();
        summary.write_varint(1, 8).unwrap();
        summary.write_string(2, "old").unwrap();
        summary.write_string(2, "new").unwrap();
        summary.write_message(6, &later_room).unwrap();
        summary.write_message(6, &first_room).unwrap();
        let mut pair = ProtoWriter::new();
        pair.write_message(2, &summary).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        let group = parse_group_summaries(response.as_bytes()).unwrap().remove(0);
        assert_eq!(group.group_id, 8);
        assert_eq!(group.name, "new");
        assert_eq!(group.default_chat_id, 9001);
        assert_eq!(group.rooms[1].chat_id, 9999);
    }

    #[test]
    fn wrong_wire_unsigned_id_becomes_zero_like_kotlin() {
        let mut room = ProtoWriter::new();
        room.write_string(1, "wrong wire").unwrap();
        let mut summary = ProtoWriter::new();
        summary.write_string(1, "wrong wire").unwrap();
        summary.write_string(5, "wrong wire").unwrap();
        summary.write_message(6, &room).unwrap();
        let mut pair = ProtoWriter::new();
        pair.write_message(2, &summary).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        let group = parse_group_summaries(response.as_bytes()).unwrap().remove(0);
        assert_eq!(group.group_id, 0);
        assert_eq!(group.default_chat_id, 0);
        assert_eq!(group.rooms[0].chat_id, 0);
    }

    #[test]
    fn raw_avatar_sources_are_preserved_for_kotlin_url_normalization() {
        let legacy = [0u8; 20];
        let ugc = b"https://steamusercontent-a.akamaihd.net/ugc/123/avatar.png";
        let mut room = ProtoWriter::new();
        room.write_varint(1, 9001).unwrap();
        let mut summary = ProtoWriter::new();
        summary.write_varint(1, 8001).unwrap();
        summary.write_varint(5, 9001).unwrap();
        summary.write_message(6, &room).unwrap();
        summary.write_bytes(11, &legacy).unwrap();
        summary.write_bytes(21, ugc).unwrap();
        let mut pair = ProtoWriter::new();
        pair.write_message(2, &summary).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        let group = parse_group_summaries(response.as_bytes()).unwrap().remove(0);
        assert_eq!(group.avatar_legacy_raw.as_slice(), &legacy);
        assert_eq!(group.avatar_ugc_raw.as_slice(), ugc);
    }

    #[test]
    fn lax_packed_member_decoder_keeps_partial_value_like_kotlin() {
        let mut room = ProtoWriter::new();
        room.write_varint(1, 9001).unwrap();
        room.write_bytes(4, &[0x81]).unwrap();
        let mut summary = ProtoWriter::new();
        summary.write_varint(1, 8001).unwrap();
        summary.write_varint(5, 9001).unwrap();
        summary.write_message(6, &room).unwrap();
        let mut pair = ProtoWriter::new();
        pair.write_message(2, &summary).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        let group = parse_group_summaries(response.as_bytes()).unwrap().remove(0);
        assert_eq!(group.rooms[0].voice_member_account_ids, vec![1]);
    }

    #[test]
    fn malformed_ack_is_delayed_until_after_group_id_validation() {
        let mut user_state = ProtoWriter::new();
        user_state.write_bytes(3, &[0x08, 0x80]).unwrap();
        let mut summary_without_id = ProtoWriter::new();
        summary_without_id.write_varint(5, 9001).unwrap();
        let mut pair = ProtoWriter::new();
        pair.write_message(1, &user_state).unwrap();
        pair.write_message(2, &summary_without_id).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        // Kotlin returns null from parseSummaryPair before parseAcknowledgements.
        assert!(parse_group_summaries(response.as_bytes()).unwrap().is_empty());
    }

    #[test]
    fn malformed_user_state_top_level_still_fails_before_missing_summary() {
        let mut pair = ProtoWriter::new();
        pair.write_bytes(1, &[0x08, 0x80]).unwrap();
        let mut response = ProtoWriter::new();
        response.write_message(1, &pair).unwrap();

        // Kotlin eagerly parses pair[1].bytes into userState before checking pair[2].
        assert!(parse_group_summaries(response.as_bytes()).is_err());
    }

    #[test]
    fn group_sort_is_stable_for_equal_recent_timestamps() {
        fn group_pair(group_id: i64) -> ProtoWriter {
            let room = room(9000 + group_id, "", 0, 100, false);
            let mut summary = ProtoWriter::new();
            summary.write_varint(1, group_id).unwrap();
            summary.write_message(6, &room).unwrap();
            let mut pair = ProtoWriter::new();
            pair.write_message(2, &summary).unwrap();
            pair
        }
        let mut response = ProtoWriter::new();
        response.write_message(1, &group_pair(1)).unwrap();
        response.write_message(1, &group_pair(2)).unwrap();

        let groups = parse_group_summaries(response.as_bytes()).unwrap();
        assert_eq!(
            groups.iter().map(|group| group.group_id).collect::<Vec<_>>(),
            vec![1, 2]
        );
    }
}
