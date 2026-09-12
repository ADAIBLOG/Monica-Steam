use crate::proto::{parse_all, ProtoError, ProtoValue, ProtoWriter};
use flate2::read::GzDecoder;
use std::io::{self, Read};

const PROTOBUF_HEADER_FLAG: u32 = 0x8000_0000;
const EMSG_MULTI: i32 = 1;
const MAX_MULTI_DEPTH: usize = 2;
const MAX_MULTI_UNPACKED_BYTES: usize = 32 * 1024 * 1024;
const MAX_ENVELOPE_BYTES: usize = 16 * 1024 * 1024;
pub const WEB_PROTOCOL_VERSION: i64 = 65_580;
pub const WEB_CLIENT_OS_TYPE: i64 = 4_294_966_596;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CmHeader {
    pub steam_id: i64,
    pub session_id: i32,
    pub job_id_source: i64,
    pub job_id_target: i64,
    pub target_job_name: Option<String>,
    pub e_result: Option<i32>,
    pub transport_error: Option<i32>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CmEnvelope {
    pub e_msg: i32,
    pub header: CmHeader,
    pub body: Vec<u8>,
}

#[derive(Debug)]
pub enum CmDecodeError {
    Proto(ProtoError),
    TooShort,
    MissingProtobufHeader,
    InvalidHeaderLength,
    MultiDepthExceeded,
    MultiMissingPayload,
    MultiSizeMismatch,
    MultiItemTruncated,
    MultiItemInvalid,
    PayloadTooLarge,
    Gzip(io::Error),
}

impl From<ProtoError> for CmDecodeError {
    fn from(value: ProtoError) -> Self {
        Self::Proto(value)
    }
}

pub fn encode_message(
    e_msg: i32,
    steam_id: i64,
    session_id: i32,
    body: &[u8],
    job_id_source: i64,
    job_id_target: i64,
    target_job_name: Option<&str>,
) -> Result<Vec<u8>, ProtoError> {
    let mut header = ProtoWriter::new();
    header.write_fixed64(1, steam_id)?;
    header.write_varint(2, session_id as i64)?;
    header.write_fixed64(10, job_id_source)?;
    header.write_fixed64(11, job_id_target)?;
    if let Some(name) = target_job_name.filter(|name| !name.is_empty()) {
        header.write_string(12, name)?;
    }
    let header = header.into_bytes();

    let mut message = Vec::with_capacity(8 + header.len() + body.len());
    let raw_e_msg = (e_msg as u32) | PROTOBUF_HEADER_FLAG;
    message.extend_from_slice(&raw_e_msg.to_le_bytes());
    message.extend_from_slice(&(header.len() as u32).to_le_bytes());
    message.extend_from_slice(&header);
    message.extend_from_slice(body);
    Ok(message)
}

pub fn web_logon_body(web_logon_token: &str) -> Result<Vec<u8>, ProtoError> {
    let mut writer = ProtoWriter::new();
    writer.write_varint(1, WEB_PROTOCOL_VERSION)?;
    writer.write_varint(7, WEB_CLIENT_OS_TYPE)?;
    writer.write_varint(32, 4)?;
    writer.write_varint(33, 2)?;
    writer.write_string(80, "anonymous")?;
    writer.write_string(103, web_logon_token)?;
    Ok(writer.into_bytes())
}

pub fn decode_messages(payload: &[u8]) -> Result<Vec<CmEnvelope>, CmDecodeError> {
    decode_messages_at_depth(payload, 0)
}

fn decode_messages_at_depth(
    payload: &[u8],
    depth: usize,
) -> Result<Vec<CmEnvelope>, CmDecodeError> {
    if depth > MAX_MULTI_DEPTH {
        return Err(CmDecodeError::MultiDepthExceeded);
    }
    if payload.len() > MAX_ENVELOPE_BYTES {
        return Err(CmDecodeError::PayloadTooLarge);
    }

    let envelope = decode_single(payload)?;
    if envelope.e_msg != EMSG_MULTI {
        return Ok(vec![envelope]);
    }

    let fields = parse_all(&envelope.body)?;
    let compressed_size = match field_i64(&fields, 1) {
        Some(value) if value > 0 => usize::try_from(value).map_err(|_| CmDecodeError::PayloadTooLarge)?,
        _ => 0,
    };
    let packed = field_bytes(&fields, 2).ok_or(CmDecodeError::MultiMissingPayload)?;

    let unpacked = if compressed_size > 0 {
        if compressed_size > MAX_MULTI_UNPACKED_BYTES {
            return Err(CmDecodeError::PayloadTooLarge);
        }
        let mut decoder = GzDecoder::new(packed);
        let mut output = Vec::with_capacity(compressed_size.min(MAX_MULTI_UNPACKED_BYTES));
        decoder
            .by_ref()
            .take((MAX_MULTI_UNPACKED_BYTES + 1) as u64)
            .read_to_end(&mut output)
            .map_err(CmDecodeError::Gzip)?;
        if output.len() > MAX_MULTI_UNPACKED_BYTES {
            return Err(CmDecodeError::PayloadTooLarge);
        }
        if output.len() != compressed_size {
            return Err(CmDecodeError::MultiSizeMismatch);
        }
        output
    } else {
        if packed.len() > MAX_MULTI_UNPACKED_BYTES {
            return Err(CmDecodeError::PayloadTooLarge);
        }
        packed.to_vec()
    };

    let mut messages = Vec::new();
    let mut offset = 0usize;
    while offset < unpacked.len() {
        if unpacked.len() - offset < 4 {
            return Err(CmDecodeError::MultiItemTruncated);
        }
        let length = u32::from_le_bytes(
            unpacked[offset..offset + 4]
                .try_into()
                .map_err(|_| CmDecodeError::MultiItemTruncated)?,
        ) as usize;
        offset += 4;
        let end = offset
            .checked_add(length)
            .ok_or(CmDecodeError::MultiItemInvalid)?;
        if length > MAX_ENVELOPE_BYTES || end > unpacked.len() {
            return Err(CmDecodeError::MultiItemInvalid);
        }
        messages.extend(decode_messages_at_depth(&unpacked[offset..end], depth + 1)?);
        offset = end;
    }
    Ok(messages)
}

fn decode_single(payload: &[u8]) -> Result<CmEnvelope, CmDecodeError> {
    if payload.len() < 8 {
        return Err(CmDecodeError::TooShort);
    }
    let raw_e_msg = u32::from_le_bytes(payload[0..4].try_into().unwrap());
    if raw_e_msg & PROTOBUF_HEADER_FLAG == 0 {
        return Err(CmDecodeError::MissingProtobufHeader);
    }
    let e_msg = (raw_e_msg & !PROTOBUF_HEADER_FLAG) as i32;
    let header_length = u32::from_le_bytes(payload[4..8].try_into().unwrap()) as usize;
    let header_end = 8usize
        .checked_add(header_length)
        .ok_or(CmDecodeError::InvalidHeaderLength)?;
    if header_end > payload.len() {
        return Err(CmDecodeError::InvalidHeaderLength);
    }

    let fields = parse_all(&payload[8..header_end])?;
    Ok(CmEnvelope {
        e_msg,
        header: CmHeader {
            steam_id: field_i64(&fields, 1).unwrap_or(0),
            session_id: field_i64(&fields, 2).unwrap_or(0) as i32,
            job_id_source: field_i64(&fields, 10).unwrap_or(-1),
            job_id_target: field_i64(&fields, 11).unwrap_or(-1),
            target_job_name: field_string(&fields, 12),
            e_result: field_i64(&fields, 13).map(|value| value as i32),
            error_message: field_string(&fields, 14),
            transport_error: field_i64(&fields, 17).map(|value| value as i32),
        },
        body: payload[header_end..].to_vec(),
    })
}

fn field_i64(fields: &[crate::proto::ProtoField], number: u32) -> Option<i64> {
    fields
        .iter()
        .find(|field| field.number == number)
        .and_then(|field| field.as_i64())
}

fn field_bytes<'a>(fields: &'a [crate::proto::ProtoField], number: u32) -> Option<&'a [u8]> {
    fields
        .iter()
        .find(|field| field.number == number)
        .and_then(|field| field.as_bytes())
}

fn field_string(fields: &[crate::proto::ProtoField], number: u32) -> Option<String> {
    fields
        .iter()
        .find(|field| field.number == number)
        .and_then(|field| match &field.value {
            ProtoValue::Bytes(bytes) if !bytes.is_empty() => {
                let text = String::from_utf8_lossy(bytes).into_owned();
                (!text.trim().is_empty()).then_some(text)
            }
            _ => None,
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::GzEncoder, Compression};
    use std::io::Write;

    #[test]
    fn cm_envelope_matches_kotlin_layout() {
        let body = [0xde, 0xad, 0xbe, 0xef];
        let encoded = encode_message(146, 76561198000000000, 42, &body, -1, 77, Some("Test.Method"))
            .unwrap();

        assert_eq!(u32::from_le_bytes(encoded[0..4].try_into().unwrap()), 146 | PROTOBUF_HEADER_FLAG);
        let header_len = u32::from_le_bytes(encoded[4..8].try_into().unwrap()) as usize;
        let header = &encoded[8..8 + header_len];
        assert_eq!(&encoded[8 + header_len..], body);

        let fields = parse_all(header).unwrap();
        assert_eq!(fields.iter().find(|field| field.number == 1).unwrap().as_i64(), Some(76561198000000000));
        assert_eq!(fields.iter().find(|field| field.number == 2).unwrap().as_i64(), Some(42));
        assert_eq!(fields.iter().find(|field| field.number == 10).unwrap().as_i64(), Some(-1));
        assert_eq!(fields.iter().find(|field| field.number == 11).unwrap().as_i64(), Some(77));
        assert_eq!(
            fields.iter().find(|field| field.number == 12).unwrap().as_utf8_lossy().as_deref(),
            Some("Test.Method")
        );
    }

    #[test]
    fn empty_target_job_name_is_omitted() {
        let encoded = encode_message(151, 0, 0, &[], -1, -1, Some("")).unwrap();
        let header_len = u32::from_le_bytes(encoded[4..8].try_into().unwrap()) as usize;
        let fields = parse_all(&encoded[8..8 + header_len]).unwrap();
        assert!(fields.iter().all(|field| field.number != 12));
    }

    #[test]
    fn web_logon_body_has_expected_fields() {
        let encoded = web_logon_body("token-value").unwrap();
        let fields = parse_all(&encoded).unwrap();
        let field = |number| fields.iter().find(|field| field.number == number).unwrap();
        assert_eq!(field(1).as_i64(), Some(WEB_PROTOCOL_VERSION));
        assert_eq!(field(7).as_i64(), Some(WEB_CLIENT_OS_TYPE));
        assert_eq!(field(32).as_i64(), Some(4));
        assert_eq!(field(33).as_i64(), Some(2));
        assert_eq!(field(80).as_utf8_lossy().as_deref(), Some("anonymous"));
        assert_eq!(field(103).as_utf8_lossy().as_deref(), Some("token-value"));
    }

    #[test]
    fn decode_single_round_trip() {
        let encoded = encode_message(147, 42, 7, b"body", 9, 11, Some("Test.Response")).unwrap();
        let messages = decode_messages(&encoded).unwrap();
        assert_eq!(messages.len(), 1);
        let message = &messages[0];
        assert_eq!(message.e_msg, 147);
        assert_eq!(message.header.steam_id, 42);
        assert_eq!(message.header.session_id, 7);
        assert_eq!(message.header.job_id_source, 9);
        assert_eq!(message.header.job_id_target, 11);
        assert_eq!(message.header.target_job_name.as_deref(), Some("Test.Response"));
        assert_eq!(message.body, b"body");
    }

    #[test]
    fn decode_gzip_multi_message() {
        let first = encode_message(147, 1, 2, b"a", -1, -1, None).unwrap();
        let second = encode_message(152, 3, 4, b"bc", -1, -1, Some("Push")).unwrap();
        let mut framed = Vec::new();
        for message in [&first, &second] {
            framed.extend_from_slice(&(message.len() as u32).to_le_bytes());
            framed.extend_from_slice(message);
        }
        let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
        encoder.write_all(&framed).unwrap();
        let compressed = encoder.finish().unwrap();

        let mut multi_body = ProtoWriter::new();
        multi_body.write_varint(1, framed.len() as i64).unwrap();
        multi_body.write_bytes(2, &compressed).unwrap();
        let multi = encode_message(EMSG_MULTI, 0, 0, &multi_body.into_bytes(), -1, -1, None).unwrap();

        let messages = decode_messages(&multi).unwrap();
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0].e_msg, 147);
        assert_eq!(messages[0].body, b"a");
        assert_eq!(messages[1].e_msg, 152);
        assert_eq!(messages[1].header.target_job_name.as_deref(), Some("Push"));
        assert_eq!(messages[1].body, b"bc");
    }

    #[test]
    fn malformed_multi_is_rejected() {
        let mut multi_body = ProtoWriter::new();
        multi_body.write_bytes(2, &[0x10, 0, 0, 0, 1]).unwrap();
        let multi = encode_message(EMSG_MULTI, 0, 0, &multi_body.into_bytes(), -1, -1, None).unwrap();
        assert!(matches!(
            decode_messages(&multi),
            Err(CmDecodeError::MultiItemInvalid)
        ));
    }
}
