//! Just enough XML for the game's own files: elements with attributes, their child elements and text, entities,
//! comments and declarations skipped. Not a general XML parser.

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Element {
    pub name: String,
    pub attrs: Vec<(String, String)>,
    pub children: Vec<Element>,
    /// The text directly inside, trimmed.
    pub text: String,
}

impl Element {
    pub fn attr(&self, name: &str) -> Option<&str> {
        self.attrs.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }

    pub fn child(&self, name: &str) -> Option<&Element> {
        self.children.iter().find(|c| c.name == name)
    }
}

/// Every element with this name in the document, at any depth (not inside another one of the same name).
pub fn elements(doc: &str, name: &str) -> Result<Vec<Element>, String> {
    let root = parse(doc)?;
    let mut out = Vec::new();
    collect(root, name, &mut out);
    Ok(out)
}

fn collect(e: Element, name: &str, out: &mut Vec<Element>) {
    if e.name == name {
        out.push(e);
    } else {
        for c in e.children {
            collect(c, name, out);
        }
    }
}

/// The document's root element.
pub fn parse(doc: &str) -> Result<Element, String> {
    let mut p = Parser { s: doc.as_bytes(), at: 0, doc };
    p.misc()?;
    let root = p.element()?;
    Ok(root)
}

struct Parser<'a> {
    s: &'a [u8],
    at: usize,
    doc: &'a str,
}

impl Parser<'_> {
    fn err(&self, what: &str) -> String {
        format!("{what} at byte {}", self.at)
    }

    fn starts(&self, p: &str) -> bool {
        self.s[self.at..].starts_with(p.as_bytes())
    }

    fn skip_until(&mut self, end: &str) -> Result<(), String> {
        match self.doc[self.at..].find(end) {
            Some(i) => {
                self.at += i + end.len();
                Ok(())
            }
            None => Err(self.err(&format!("no {end}"))),
        }
    }

    fn spaces(&mut self) {
        while self.at < self.s.len() && self.s[self.at].is_ascii_whitespace() {
            self.at += 1;
        }
    }

    /// Declarations, comments and spaces before the root element.
    fn misc(&mut self) -> Result<(), String> {
        loop {
            self.spaces();
            if self.starts("<?") {
                self.skip_until("?>")?;
            } else if self.starts("<!--") {
                self.skip_until("-->")?;
            } else if self.starts("<!") {
                self.skip_until(">")?;
            } else {
                return Ok(());
            }
        }
    }

    fn name(&mut self) -> String {
        let start = self.at;
        while self.at < self.s.len() && !self.s[self.at].is_ascii_whitespace() && !b"/>=".contains(&self.s[self.at]) {
            self.at += 1;
        }
        self.doc[start..self.at].to_string()
    }

    fn element(&mut self) -> Result<Element, String> {
        if !self.starts("<") {
            return Err(self.err("expected an element"));
        }
        self.at += 1;
        let mut e = Element { name: self.name(), ..Default::default() };
        loop {
            self.spaces();
            if self.starts("/>") {
                self.at += 2;
                return Ok(e);
            }
            if self.starts(">") {
                self.at += 1;
                break;
            }
            let n = self.name();
            self.spaces();
            if !self.starts("=") {
                return Err(self.err("expected ="));
            }
            self.at += 1;
            self.spaces();
            let q = *self.s.get(self.at).ok_or_else(|| self.err("cut off"))?;
            if q != b'"' && q != b'\'' {
                return Err(self.err("expected a quote"));
            }
            self.at += 1;
            let start = self.at;
            while self.at < self.s.len() && self.s[self.at] != q {
                self.at += 1;
            }
            let v = unescape(&self.doc[start..self.at]);
            self.at += 1;
            e.attrs.push((n, v));
        }
        let mut text = String::new();
        loop {
            if self.at >= self.s.len() {
                return Err(self.err(&format!("<{}> not closed", e.name)));
            }
            if self.starts("</") {
                self.at += 2;
                let n = self.name();
                if n != e.name {
                    return Err(self.err(&format!("</{n}> closes <{}>", e.name)));
                }
                self.spaces();
                self.skip_until(">")?;
                e.text = unescape(text.trim());
                return Ok(e);
            } else if self.starts("<!--") {
                self.skip_until("-->")?;
            } else if self.starts("<![CDATA[") {
                let start = self.at + 9;
                self.skip_until("]]>")?;
                text.push_str(&self.doc[start..self.at - 3]);
            } else if self.starts("<?") {
                self.skip_until("?>")?;
            } else if self.starts("<") {
                e.children.push(self.element()?);
            } else {
                let start = self.at;
                while self.at < self.s.len() && self.s[self.at] != b'<' {
                    self.at += 1;
                }
                text.push_str(&self.doc[start..self.at]);
            }
        }
    }
}

fn unescape(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let Some(end) = rest.find(';') else { break };
        let ent = &rest[1..end];
        let c = match ent {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            _ => ent
                .strip_prefix("#x")
                .map(|h| u32::from_str_radix(h, 16))
                .or_else(|| ent.strip_prefix('#').map(str::parse))
                .and_then(Result::ok)
                .and_then(char::from_u32),
        };
        match c {
            Some(c) => {
                out.push(c);
                rest = &rest[end + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn elements_attributes_text() {
        let d = "<?xml version=\"1.0\"?>\n<!-- c --><Objects><Object type='0x1' id=\"A &amp; B\"><!-- x --><Player/>\
                 <Max max=\"5\">10</Max><Text><![CDATA[a<b]]> &#65;</Text></Object></Objects>";
        let o = elements(d, "Object").unwrap();
        assert_eq!((o[0].attr("type"), o[0].attr("id")), (Some("0x1"), Some("A & B")));
        assert_eq!(o[0].child("Max").unwrap().attr("max"), Some("5"));
        assert_eq!(o[0].child("Max").unwrap().text, "10");
        assert_eq!(o[0].child("Text").unwrap().text, "a<b A");
        assert!(o[0].child("Player").is_some());
        assert!(parse("<a><b></a>").is_err());
    }
}
