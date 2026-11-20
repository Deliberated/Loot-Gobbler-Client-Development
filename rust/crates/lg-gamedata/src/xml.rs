//! Just enough XML for the game's own files, with the two views the Java client reads them through:
//!
//! - DOM (`org.w3c.dom`): elements at any depth by name ([`Element::descendants`]), an attribute or `""`
//!   ([`Element::attr_or_empty`]), all text inside ([`Element::text_content`])
//! - `StringXML` (RealmShark's wrapper): an element's children are its first child node, then its attributes sorted by
//!   name, then its other child nodes, text and comments included ([`Element::java_children`])
//!
//! Entities are decoded, comments and declarations kept apart from text. Not a general XML parser.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Node {
    Element(Element),
    Text(String),
    /// A CDATA section: text that DOM keeps as a node of its own.
    CData(String),
    Comment(String),
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Element {
    pub name: String,
    /// In the document's order.
    pub attrs: Vec<(String, String)>,
    pub content: Vec<Node>,
}

/// A node as `StringXML` sees it: its name (`#text` for text), its value (None for an element).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JavaNode<'a> {
    pub name: &'a str,
    pub value: Option<&'a str>,
    /// The element, if it is one.
    pub element: Option<&'a Element>,
}

impl Element {
    pub fn attr(&self, name: &str) -> Option<&str> {
        self.attrs.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }

    /// DOM `getAttribute`: `""` when missing.
    pub fn attr_or_empty(&self, name: &str) -> &str {
        self.attr(name).unwrap_or("")
    }

    /// The child elements.
    pub fn children(&self) -> impl Iterator<Item = &Element> {
        self.content.iter().filter_map(|n| match n {
            Node::Element(e) => Some(e),
            _ => None,
        })
    }

    pub fn child(&self, name: &str) -> Option<&Element> {
        self.children().find(|c| c.name == name)
    }

    /// The text directly inside, trimmed.
    pub fn text(&self) -> String {
        let mut s = String::new();
        for n in &self.content {
            if let Node::Text(t) | Node::CData(t) = n {
                s.push_str(t);
            }
        }
        s.trim().to_string()
    }

    /// DOM `getTextContent`: all text inside, at any depth, untrimmed (comments left out).
    pub fn text_content(&self) -> String {
        let mut s = String::new();
        fn walk(e: &Element, s: &mut String) {
            for n in &e.content {
                match n {
                    Node::Text(t) | Node::CData(t) => s.push_str(t),
                    Node::Element(c) => walk(c, s),
                    Node::Comment(_) => {}
                }
            }
        }
        walk(self, &mut s);
        s
    }

    /// DOM `getElementsByTagName`: the elements with this name inside, at any depth, in document order.
    pub fn descendants<'a>(&'a self, name: &'a str) -> Vec<&'a Element> {
        let mut out = Vec::new();
        fn walk<'a>(e: &'a Element, name: &str, out: &mut Vec<&'a Element>) {
            for c in e.children() {
                if c.name == name {
                    out.push(c);
                }
                walk(c, name, out);
            }
        }
        walk(self, name, &mut out);
        out
    }

    /// `StringXML`'s children: the first child node, the attributes sorted by name, the other child nodes.
    pub fn java_children(&self) -> Vec<JavaNode<'_>> {
        fn as_java(n: &Node) -> JavaNode<'_> {
            match n {
                Node::Element(e) => JavaNode { name: &e.name, value: None, element: Some(e) },
                Node::Text(t) => JavaNode { name: "#text", value: Some(t), element: None },
                Node::CData(t) => JavaNode { name: "#cdata-section", value: Some(t), element: None },
                Node::Comment(t) => JavaNode { name: "#comment", value: Some(t), element: None },
            }
        }
        let mut out = Vec::new();
        let mut rest = self.content.iter();
        if let Some(first) = rest.next() {
            out.push(as_java(first));
        }
        let mut attrs: Vec<&(String, String)> = self.attrs.iter().collect();
        attrs.sort_by(|a, b| a.0.cmp(&b.0));
        out.extend(attrs.into_iter().map(|(n, v)| JavaNode { name: n, value: Some(v), element: None }));
        out.extend(rest.map(as_java));
        out
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
        for n in e.content {
            if let Node::Element(c) = n {
                collect(c, name, out);
            }
        }
    }
}

/// The document's root element.
pub fn parse(doc: &str) -> Result<Element, String> {
    let mut p = Parser { s: doc.as_bytes(), at: 0, doc };
    p.misc()?;
    p.element()
}

/// Where the root element ends (the byte after it).
pub fn root_end(doc: &str) -> Option<usize> {
    let mut p = Parser { s: doc.as_bytes(), at: 0, doc };
    p.misc().ok()?;
    p.element().ok()?;
    Some(p.at)
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

    /// Skips to after `end`, giving what came before it.
    fn until(&mut self, end: &str) -> Result<String, String> {
        match self.doc[self.at..].find(end) {
            Some(i) => {
                let s = self.doc[self.at..self.at + i].to_string();
                self.at += i + end.len();
                Ok(s)
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
                self.until("?>")?;
            } else if self.starts("<!--") {
                self.until("-->")?;
            } else if self.starts("<!") {
                self.until(">")?;
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
            if n.is_empty() {
                return Err(self.err("expected an attribute"));
            }
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
            // DOM normalizes whitespace characters in attribute values to spaces
            let v = unescape(&self.doc[start..self.at].replace(['\n', '\r', '\t'], " "));
            self.at += 1;
            e.attrs.push((n, v));
        }
        let mut text = String::new();
        let flush = |text: &mut String, content: &mut Vec<Node>| {
            if !text.is_empty() {
                content.push(Node::Text(unescape(&std::mem::take(text))));
            }
        };
        loop {
            if self.at >= self.s.len() {
                return Err(self.err(&format!("<{}> not closed", e.name)));
            }
            if self.starts("</") {
                flush(&mut text, &mut e.content);
                self.at += 2;
                let n = self.name();
                if n != e.name {
                    return Err(self.err(&format!("</{n}> closes <{}>", e.name)));
                }
                self.spaces();
                if !self.starts(">") {
                    return Err(self.err("expected >"));
                }
                self.at += 1;
                return Ok(e);
            } else if self.starts("<!--") {
                flush(&mut text, &mut e.content);
                self.at += 4;
                let c = self.until("-->")?;
                e.content.push(Node::Comment(c));
            } else if self.starts("<![CDATA[") {
                flush(&mut text, &mut e.content);
                self.at += 9;
                let c = self.until("]]>")?;
                e.content.push(Node::CData(c));
            } else if self.starts("<?") {
                flush(&mut text, &mut e.content);
                self.until("?>")?;
            } else if self.starts("<") {
                flush(&mut text, &mut e.content);
                let c = self.element()?;
                e.content.push(Node::Element(c));
            } else {
                let start = self.at;
                while self.at < self.s.len() && self.s[self.at] != b'<' {
                    self.at += 1;
                }
                // XML line ends are \n (a \r\n or lone \r becomes \n)
                text.push_str(&self.doc[start..self.at].replace("\r\n", "\n").replace('\r', "\n"));
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
        assert_eq!(o[0].child("Max").unwrap().text(), "10");
        assert_eq!(o[0].child("Text").unwrap().text(), "a<b A");
        assert!(o[0].child("Player").is_some());
        assert!(parse("<a><b></a>").is_err());
    }

    #[test]
    fn dom_and_string_xml_views() {
        let r = parse("<R><O id='p' b='2' a='1'>first<X t='q'>in<Y>deep</Y></X><!--c-->last</O><O/></R>").unwrap();
        let o = r.descendants("O");
        assert_eq!(o.len(), 2);
        assert_eq!(r.descendants("Y")[0].text_content(), "deep");
        assert_eq!(o[0].text_content(), "firstindeeplast");
        assert_eq!(o[1].attr_or_empty("id"), "");
        let names: Vec<_> = o[0].java_children().iter().map(|n| (n.name, n.value)).collect();
        assert_eq!(
            names,
            [
                ("#text", Some("first")),
                ("a", Some("1")),
                ("b", Some("2")),
                ("id", Some("p")),
                ("X", None),
                ("#comment", Some("c")),
                ("#text", Some("last"))
            ]
        );
    }
}
