//! Gson's rules for reading a JSON value into a Java field (Java: `GSON.fromJson(json, Class)`), where the app
//! reads JSON into classes: a field missing keeps its default, `null` leaves a primitive as it was and sets an
//! object field to null, numbers may come as strings, a value of the wrong kind throws ([`Thrown`]).

use crate::java::Thrown;
use lg_world::json::Json;

/// What a primitive or boxed field gets: None = left as it was (missing, or `null` for a primitive).
pub type Read<T> = Result<Option<T>, Thrown>;

fn number_text(v: &Json) -> Result<Option<&str>, Thrown> {
    match v {
        Json::Null => Ok(None),
        Json::Number(n) | Json::String(n) => Ok(Some(n)),
        _ => Err(Thrown),
    }
}

/// `JsonReader.nextInt`: an int, or a number (or numeric string) that is exactly one.
pub fn int(v: Option<&Json>) -> Read<i32> {
    let Some(t) = v.map(number_text).transpose()?.flatten() else { return Ok(None) };
    if let Ok(i) = t.parse::<i32>() {
        return Ok(Some(i));
    }
    let d: f64 = t.parse().map_err(|_| Thrown)?;
    let i = d as i32;
    if f64::from(i) != d {
        return Err(Thrown);
    }
    Ok(Some(i))
}

/// `JsonReader.nextLong`.
pub fn long(v: Option<&Json>) -> Read<i64> {
    let Some(t) = v.map(number_text).transpose()?.flatten() else { return Ok(None) };
    if let Ok(i) = t.parse::<i64>() {
        return Ok(Some(i));
    }
    let d: f64 = t.parse().map_err(|_| Thrown)?;
    let i = d as i64;
    if i as f64 != d {
        return Err(Thrown);
    }
    Ok(Some(i))
}

/// `JsonReader.nextDouble` (lenient: NaN and Infinity too).
pub fn double(v: Option<&Json>) -> Read<f64> {
    let Some(t) = v.map(number_text).transpose()?.flatten() else { return Ok(None) };
    match t {
        "NaN" => Ok(Some(f64::NAN)),
        "Infinity" => Ok(Some(f64::INFINITY)),
        "-Infinity" => Ok(Some(f64::NEG_INFINITY)),
        _ => t.parse().map(Some).map_err(|_| Thrown),
    }
}

/// A `String` field: Some(None) for `null`, a number as written, a boolean as text.
pub fn string(v: Option<&Json>) -> Result<Option<Option<String>>, Thrown> {
    match v {
        None => Ok(None),
        Some(Json::Null) => Ok(Some(None)),
        Some(Json::String(s) | Json::Number(s)) => Ok(Some(Some(s.clone()))),
        Some(Json::Bool(b)) => Ok(Some(Some(b.to_string()))),
        Some(_) => Err(Thrown),
    }
}

/// A `boolean` field: true/false, or a string read with `Boolean.parseBoolean`.
pub fn boolean(v: Option<&Json>) -> Read<bool> {
    match v {
        None | Some(Json::Null) => Ok(None),
        Some(Json::Bool(b)) => Ok(Some(*b)),
        Some(Json::String(s)) => Ok(Some(s.eq_ignore_ascii_case("true"))),
        Some(_) => Err(Thrown),
    }
}

/// An `int[]` field: Some(None) for `null`; a `null` element throws.
pub fn ints(v: Option<&Json>) -> Result<Option<Option<Vec<i32>>>, Thrown> {
    match v {
        None => Ok(None),
        Some(Json::Null) => Ok(Some(None)),
        Some(Json::Array(a)) => Ok(Some(Some(a.iter().map(|x| int(Some(x))?.ok_or(Thrown)).collect::<Result<_, _>>()?))),
        Some(_) => Err(Thrown),
    }
}

/// An `int[][]` as read: the arrays, a `null` one as None.
pub type Ints2 = Vec<Option<Vec<i32>>>;

/// An `int[][]` field: a `null` inner array stays null (here: None).
pub fn ints2(v: Option<&Json>) -> Result<Option<Option<Ints2>>, Thrown> {
    match v {
        None => Ok(None),
        Some(Json::Null) => Ok(Some(None)),
        Some(Json::Array(a)) => Ok(Some(Some(a.iter().map(|x| ints(Some(x)).map(|r| r.flatten())).collect::<Result<_, _>>()?))),
        Some(_) => Err(Thrown),
    }
}

/// Reads an object's fields: `None` from `of` when the value is not an object (`null` is the caller's).
pub fn object(v: &Json) -> Result<&Json, Thrown> {
    match v {
        Json::Object(_) => Ok(v),
        _ => Err(Thrown),
    }
}

/// The value of a list of objects, `null` elements and a `null` list left out (Java-written files have none).
pub fn objects<T>(v: Option<&Json>, item: impl Fn(&Json) -> Result<T, Thrown>) -> Result<Option<Vec<T>>, Thrown> {
    Ok(list(v, item)?.map(|l| l.unwrap_or_default().into_iter().flatten().collect()))
}

/// A list root (`fromJson(..., ArrayList<T>)`): `null` or nothing is no list.
pub fn root_list<T>(text: &str, item: impl Fn(&Json) -> Result<T, Thrown>) -> Result<Vec<T>, Thrown> {
    if crate::java::is_blank(text) {
        return Ok(Vec::new());
    }
    let v = lg_world::json::parse(text).map_err(|_| Thrown)?;
    Ok(objects(Some(&v), item)?.unwrap_or_default())
}

/// A `List<T>` field: Some(None) for `null`, else each element read by `item` (None for a `null` element).
pub fn list<T>(v: Option<&Json>, item: impl Fn(&Json) -> Result<T, Thrown>) -> Result<Option<Option<Vec<Option<T>>>>, Thrown> {
    match v {
        None => Ok(None),
        Some(Json::Null) => Ok(Some(None)),
        Some(Json::Array(a)) => {
            Ok(Some(Some(a.iter().map(|x| if *x == Json::Null { Ok(None) } else { item(x).map(Some) }).collect::<Result<_, _>>()?)))
        }
        Some(_) => Err(Thrown),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn as_gson() {
        let n = |s: &str| Json::Number(s.into());
        assert_eq!(int(Some(&n("5"))), Ok(Some(5)));
        assert_eq!(int(Some(&n("5.0"))), Ok(Some(5)));
        assert_eq!(int(Some(&n("5.5"))), Err(Thrown));
        assert_eq!(int(Some(&Json::String("7".into()))), Ok(Some(7)));
        assert_eq!(int(Some(&Json::Null)), Ok(None));
        assert_eq!(int(Some(&Json::Bool(true))), Err(Thrown));
        assert_eq!(int(None), Ok(None));
        assert_eq!(string(Some(&n("12"))), Ok(Some(Some("12".into()))));
        assert_eq!(string(Some(&Json::Array(vec![]))), Err(Thrown));
        assert_eq!(double(Some(&n("1e3"))), Ok(Some(1000.0)));
    }
}
