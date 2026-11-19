//! Java's string handling where the app's parsing depends on it.

/// Thrown: where Java throws (a `NumberFormatException`, an index out of range...).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Thrown;

/// `Integer.parseInt`: an optional sign and digits, nothing else (no spaces).
pub fn parse_int(s: &str) -> Result<i32, Thrown> {
    s.parse().map_err(|_| Thrown)
}

/// `Short.parseShort`.
pub fn parse_short(s: &str) -> Result<i16, Thrown> {
    s.parse().map_err(|_| Thrown)
}

/// `Long.parseLong`.
pub fn parse_long(s: &str) -> Result<i64, Thrown> {
    s.parse().map_err(|_| Thrown)
}

/// `String.split` with one plain character: trailing empty parts dropped (a string of only separators gives none).
pub fn split(s: &str, sep: char) -> Vec<&str> {
    let mut v: Vec<&str> = s.split(sep).collect();
    while v.len() > 1 && v.last() == Some(&"") {
        v.pop();
    }
    if v == [""] && !s.is_empty() {
        v.clear();
    }
    v
}

/// `String.trim`: what is at or below a space goes from both ends.
pub fn trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// `String.isBlank`.
pub fn is_blank(s: &str) -> bool {
    s.chars().all(char::is_whitespace)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn as_java() {
        assert_eq!((parse_int("+5"), parse_int("-0"), parse_int(" 5"), parse_int("")), (Ok(5), Ok(0), Err(Thrown), Err(Thrown)));
        assert_eq!(parse_short("40000"), Err(Thrown));
        assert_eq!(split("a,,b,,", ','), ["a", "", "b"]);
        assert_eq!(split(",,", ','), Vec::<&str>::new());
        assert_eq!(split("", ','), [""]);
        assert_eq!(trim("\t x \n"), "x");
    }
}
