//! Buienradar 2-hour rain nowcast.
//!
//! Free to use on condition of crediting buienradar.nl with a link to
//! <https://www.buienradar.nl>. The response is plain text, one line per
//! 5 minutes: `value|HH:MM`, where `value` is 0..=255.

use crate::{Error, RainStep};

pub fn raintext_url(latitude: f64, longitude: f64) -> String {
    format!("https://gpsgadget.buienradar.nl/data/raintext?lat={latitude:.2}&lon={longitude:.2}")
}

/// Converts Buienradar's 0..=255 value to mm/h (`10^((value - 109) / 32)`).
pub fn value_to_mm_per_hour(value: u8) -> f32 {
    if value == 0 {
        0.0
    } else {
        10f32.powf((f32::from(value) - 109.0) / 32.0)
    }
}

pub fn parse_raintext(text: &str) -> Result<Vec<RainStep>, Error> {
    text.lines()
        .enumerate()
        .map(|(i, line)| (i + 1, line.trim()))
        .filter(|(_, line)| !line.is_empty())
        .map(|(line_no, line)| {
            parse_line(line).ok_or_else(|| Error::Buienradar {
                line: line_no,
                text: line.to_owned(),
            })
        })
        .collect()
}

fn parse_line(line: &str) -> Option<RainStep> {
    let (value, time) = line.split_once('|')?;
    let (hour, minute) = time.split_once(':')?;
    let (hour, minute) = (hour.parse::<u8>().ok()?, minute.parse::<u8>().ok()?);
    if hour > 23 || minute > 59 {
        return None;
    }
    Some(RainStep {
        hour,
        minute,
        mm_per_hour: value_to_mm_per_hour(value.parse().ok()?),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_values() {
        assert_eq!(value_to_mm_per_hour(0), 0.0);
        assert!((value_to_mm_per_hour(109) - 1.0).abs() < 1e-6);
        assert!((value_to_mm_per_hour(141) - 10.0).abs() < 1e-4);
        assert!(value_to_mm_per_hour(77) < 0.11);
    }

    #[test]
    fn parses_lines_with_crlf() {
        let steps = parse_raintext("000|14:15\r\n109|14:20\r\n141|14:25\r\n").unwrap();
        assert_eq!(steps.len(), 3);
        assert_eq!((steps[0].hour, steps[0].minute), (14, 15));
        assert_eq!(steps[0].mm_per_hour, 0.0);
        assert!((steps[2].mm_per_hour - 10.0).abs() < 1e-4);
    }

    #[test]
    fn rejects_garbage() {
        assert!(matches!(
            parse_raintext("000|14:15\nnope\n"),
            Err(Error::Buienradar { line: 2, .. })
        ));
        assert!(parse_raintext("300|14:15").is_err());
        assert!(parse_raintext("010|25:00").is_err());
    }
}
