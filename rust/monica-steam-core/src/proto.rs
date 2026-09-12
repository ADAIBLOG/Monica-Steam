#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtoError {
    InvalidFieldNumber,
    UnsupportedWireType(u8),
    TruncatedVarint,
    VarintOverflow,
    TruncatedField,
    LengthOverflow,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtoValue {
    Varint(u64),
    Fixed64(u64),
    Bytes(Vec<u8>),
    Fixed32(u32),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProtoField {
    pub number: u32,
    pub value: ProtoValue,
}

impl ProtoField {
    pub fn wire_type(&self) -> u8 {
        match &self.value {
            ProtoValue::Varint(_) => 0,
            ProtoValue::Fixed64(_) => 1,
            ProtoValue::Bytes(_) => 2,
            ProtoValue::Fixed32(_) => 5,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match &self.value {
            ProtoValue::Varint(value) | ProtoValue::Fixed64(value) => Some(*value as i64),
            ProtoValue::Fixed32(value) => Some(*value as i64),
            ProtoValue::Bytes(_) => None,
        }
    }

    pub fn as_utf8_lossy(&self) -> Option<String> {
        match &self.value {
            ProtoValue::Bytes(bytes) => Some(String::from_utf8_lossy(bytes).into_owned()),
            _ => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&[u8]> {
        match &self.value {
            ProtoValue::Bytes(bytes) => Some(bytes),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtoValueRef<'a> {
    Varint(u64),
    Fixed64(u64),
    Bytes(&'a [u8]),
    Fixed32(u32),
}

impl<'a> ProtoValueRef<'a> {
    fn into_owned(self) -> ProtoValue {
        match self {
            Self::Varint(value) => ProtoValue::Varint(value),
            Self::Fixed64(value) => ProtoValue::Fixed64(value),
            Self::Bytes(bytes) => ProtoValue::Bytes(bytes.to_vec()),
            Self::Fixed32(value) => ProtoValue::Fixed32(value),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProtoFieldRef<'a> {
    pub number: u32,
    pub value: ProtoValueRef<'a>,
}

impl<'a> ProtoFieldRef<'a> {
    pub fn wire_type(&self) -> u8 {
        match self.value {
            ProtoValueRef::Varint(_) => 0,
            ProtoValueRef::Fixed64(_) => 1,
            ProtoValueRef::Bytes(_) => 2,
            ProtoValueRef::Fixed32(_) => 5,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self.value {
            ProtoValueRef::Varint(value) | ProtoValueRef::Fixed64(value) => Some(value as i64),
            ProtoValueRef::Fixed32(value) => Some(value as i64),
            ProtoValueRef::Bytes(_) => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&'a [u8]> {
        match self.value {
            ProtoValueRef::Bytes(bytes) => Some(bytes),
            _ => None,
        }
    }
}

#[derive(Debug, Default, Clone)]
pub struct ProtoWriter {
    out: Vec<u8>,
}

impl ProtoWriter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.out
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.out
    }

    pub fn write_varint(&mut self, field: u32, value: i64) -> Result<(), ProtoError> {
        self.write_tag(field, 0)?;
        write_varint_raw(&mut self.out, value as u64);
        Ok(())
    }

    pub fn write_uint64(&mut self, field: u32, value: u64) -> Result<(), ProtoError> {
        self.write_tag(field, 0)?;
        write_varint_raw(&mut self.out, value);
        Ok(())
    }

    pub fn write_bool(&mut self, field: u32, value: bool) -> Result<(), ProtoError> {
        self.write_varint(field, if value { 1 } else { 0 })
    }

    pub fn write_string(&mut self, field: u32, value: &str) -> Result<(), ProtoError> {
        self.write_bytes(field, value.as_bytes())
    }

    pub fn write_bytes(&mut self, field: u32, bytes: &[u8]) -> Result<(), ProtoError> {
        self.write_tag(field, 2)?;
        write_varint_raw(&mut self.out, bytes.len() as u64);
        self.out.extend_from_slice(bytes);
        Ok(())
    }

    pub fn write_packed_varints<I>(&mut self, field: u32, values: I) -> Result<(), ProtoError>
    where
        I: IntoIterator<Item = i64>,
    {
        let mut packed = Vec::new();
        for value in values {
            write_varint_raw(&mut packed, value as u64);
        }
        self.write_bytes(field, &packed)
    }

    pub fn write_message(&mut self, field: u32, message: &ProtoWriter) -> Result<(), ProtoError> {
        self.write_bytes(field, message.as_bytes())
    }

    pub fn write_fixed64(&mut self, field: u32, value: i64) -> Result<(), ProtoError> {
        self.write_tag(field, 1)?;
        self.out.extend_from_slice(&(value as u64).to_le_bytes());
        Ok(())
    }

    pub fn write_fixed32(&mut self, field: u32, value: u32) -> Result<(), ProtoError> {
        self.write_tag(field, 5)?;
        self.out.extend_from_slice(&value.to_le_bytes());
        Ok(())
    }

    fn write_tag(&mut self, field: u32, wire_type: u8) -> Result<(), ProtoError> {
        if field == 0 || field > 0x1fff_ffff {
            return Err(ProtoError::InvalidFieldNumber);
        }
        write_varint_raw(&mut self.out, ((field as u64) << 3) | wire_type as u64);
        Ok(())
    }
}

pub fn parse_all(data: &[u8]) -> Result<Vec<ProtoField>, ProtoError> {
    Ok(parse_all_ref(data)?
        .into_iter()
        .map(|field| ProtoField {
            number: field.number,
            value: field.value.into_owned(),
        })
        .collect())
}

pub fn parse_all_ref(data: &[u8]) -> Result<Vec<ProtoFieldRef<'_>>, ProtoError> {
    let mut reader = ProtoReader::new(data);
    let mut fields = Vec::new();
    while !reader.is_finished() {
        let key = reader.read_varint_raw()?;
        let number = (key >> 3) as u32;
        if number == 0 {
            return Err(ProtoError::InvalidFieldNumber);
        }
        let wire_type = (key & 0x07) as u8;
        let value = match wire_type {
            0 => ProtoValueRef::Varint(reader.read_varint_raw()?),
            1 => ProtoValueRef::Fixed64(u64::from_le_bytes(reader.read_array::<8>()?)),
            2 => {
                let length = reader.read_varint_raw()?;
                let length = usize::try_from(length).map_err(|_| ProtoError::LengthOverflow)?;
                ProtoValueRef::Bytes(reader.read_bytes(length)?)
            }
            5 => ProtoValueRef::Fixed32(u32::from_le_bytes(reader.read_array::<4>()?)),
            other => return Err(ProtoError::UnsupportedWireType(other)),
        };
        fields.push(ProtoFieldRef { number, value });
    }
    Ok(fields)
}

pub fn decode_packed_varints(bytes: &[u8]) -> Result<Vec<i64>, ProtoError> {
    let mut reader = ProtoReader::new(bytes);
    let mut values = Vec::new();
    while !reader.is_finished() {
        values.push(reader.read_varint_raw()? as i64);
    }
    Ok(values)
}

fn write_varint_raw(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8 & 0x7f) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

struct ProtoReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> ProtoReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    fn is_finished(&self) -> bool {
        self.pos >= self.data.len()
    }

    fn read_varint_raw(&mut self) -> Result<u64, ProtoError> {
        let mut result = 0u64;
        for shift in (0..=63).step_by(7) {
            let Some(byte) = self.data.get(self.pos).copied() else {
                return Err(ProtoError::TruncatedVarint);
            };
            self.pos += 1;
            if shift == 63 && byte > 1 {
                return Err(ProtoError::VarintOverflow);
            }
            result |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                return Ok(result);
            }
        }
        Err(ProtoError::VarintOverflow)
    }

    fn read_bytes(&mut self, length: usize) -> Result<&'a [u8], ProtoError> {
        let end = self.pos.checked_add(length).ok_or(ProtoError::LengthOverflow)?;
        if end > self.data.len() {
            return Err(ProtoError::TruncatedField);
        }
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    fn read_array<const N: usize>(&mut self) -> Result<[u8; N], ProtoError> {
        let bytes = self.read_bytes(N)?;
        let mut output = [0u8; N];
        output.copy_from_slice(bytes);
        Ok(output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mixed_fields_round_trip_without_big_integer() {
        let mut nested = ProtoWriter::new();
        nested.write_string(1, "nested").unwrap();

        let mut writer = ProtoWriter::new();
        writer.write_varint(1, 150).unwrap();
        writer.write_varint(2, -1).unwrap();
        writer.write_string(3, "steam").unwrap();
        writer.write_fixed64(4, -2).unwrap();
        writer.write_fixed32(5, u32::MAX).unwrap();
        writer.write_message(6, &nested).unwrap();

        let fields = parse_all(writer.as_bytes()).unwrap();
        assert_eq!(fields.len(), 6);
        assert_eq!(fields[0].as_i64(), Some(150));
        assert_eq!(fields[1].as_i64(), Some(-1));
        assert_eq!(fields[2].as_utf8_lossy().as_deref(), Some("steam"));
        assert_eq!(fields[3].as_i64(), Some(-2));
        assert_eq!(fields[4].as_i64(), Some(u32::MAX as i64));
        assert_eq!(
            parse_all(fields[5].as_bytes().unwrap()).unwrap()[0]
                .as_utf8_lossy()
                .as_deref(),
            Some("nested")
        );
    }

    #[test]
    fn borrowed_parser_points_into_the_original_buffer() {
        let mut writer = ProtoWriter::new();
        writer.write_string(1, "borrowed").unwrap();
        let bytes = writer.into_bytes();
        let fields = parse_all_ref(&bytes).unwrap();
        let borrowed = fields[0].as_bytes().unwrap();
        assert_eq!(borrowed, b"borrowed");
        let start = bytes.as_ptr() as usize;
        let end = start + bytes.len();
        let borrowed_ptr = borrowed.as_ptr() as usize;
        assert!(borrowed_ptr >= start && borrowed_ptr < end);
    }

    #[test]
    fn owned_and_borrowed_parsers_have_identical_wire_semantics() {
        let mut writer = ProtoWriter::new();
        writer.write_varint(1, -1).unwrap();
        writer.write_fixed64(2, -2).unwrap();
        writer.write_bytes(3, b"bytes").unwrap();
        writer.write_fixed32(4, u32::MAX).unwrap();
        let bytes = writer.into_bytes();

        let owned = parse_all(&bytes).unwrap();
        let borrowed = parse_all_ref(&bytes).unwrap();
        assert_eq!(owned.len(), borrowed.len());
        for (owned, borrowed) in owned.iter().zip(&borrowed) {
            assert_eq!(owned.number, borrowed.number);
            assert_eq!(owned.wire_type(), borrowed.wire_type());
            assert_eq!(owned.as_i64(), borrowed.as_i64());
            assert_eq!(owned.as_bytes(), borrowed.as_bytes());
        }
    }

    #[test]
    fn unsigned_u64_max_matches_kotlin_varint_semantics() {
        let mut writer = ProtoWriter::new();
        writer.write_uint64(1, u64::MAX).unwrap();
        let bytes = writer.into_bytes();
        assert_eq!(bytes.len(), 11); // one-byte tag + ten-byte uint64 varint
        let fields = parse_all(&bytes).unwrap();
        assert_eq!(fields[0].as_i64(), Some(-1));
    }

    #[test]
    fn packed_varints_round_trip_signed_bit_patterns() {
        let mut writer = ProtoWriter::new();
        writer.write_packed_varints(1, [1, 127, 128, -1]).unwrap();
        let fields = parse_all(writer.as_bytes()).unwrap();
        assert_eq!(
            decode_packed_varints(fields[0].as_bytes().unwrap()).unwrap(),
            vec![1, 127, 128, -1]
        );
    }

    #[test]
    fn malformed_payloads_fail_instead_of_reading_past_end() {
        assert_eq!(
            parse_all(&[0x0a, 0x05, 0x01]),
            Err(ProtoError::TruncatedField)
        );
        assert_eq!(
            parse_all(&[0x08, 0x80]),
            Err(ProtoError::TruncatedVarint)
        );
        assert_eq!(
            parse_all(&[0x0b]),
            Err(ProtoError::UnsupportedWireType(3))
        );
    }
}
