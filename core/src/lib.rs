//! Shared core of the weather-buddy app.
//!
//! The pipeline is pure and offline-testable:
//!
//! 1. Fetch JSON/text from the weather APIs (done by the platform layer) and
//!    turn it into [`Conditions`] with [`openmeteo`] and [`buienradar`].
//! 2. [`plan`] decides what the scene looks like and how the buddy is dressed.
//! 3. The resulting [`RenderPlan::layers`] is an ordered list of asset paths
//!    that the platform stacks bottom-to-top into the final picture.

/// Declares a fieldless enum with a stable kebab-case `slug()` (used in asset
/// paths) and an `ALL` list (used to enumerate every asset a style must ship).
macro_rules! slug_enum {
    ($(#[$meta:meta])* pub enum $name:ident { $($variant:ident => $slug:literal),+ $(,)? }) => {
        $(#[$meta])*
        pub enum $name { $($variant),+ }

        impl $name {
            pub const ALL: &'static [$name] = &[$($name::$variant),+];

            pub fn slug(self) -> &'static str {
                match self { $($name::$variant => $slug),+ }
            }
        }
    };
}

pub mod buienradar;
pub mod conditions;
pub mod openmeteo;
pub mod outfit;
pub mod scene;
pub mod style;

pub use conditions::{Conditions, RainStep};
pub use outfit::{dress, Accessory, Bottom, Expression, Footwear, Outerwear, Outfit, Top};
pub use scene::{Precipitation, Scene, Sky, TimeOfDay, Wind};
pub use style::{Layer, Style};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("invalid Open-Meteo response: {0}")]
    OpenMeteo(#[from] serde_json::Error),
    #[error("Open-Meteo response has no value for `{0}`")]
    MissingField(&'static str),
    #[error("invalid Buienradar line {line}: {text:?}")]
    Buienradar { line: usize, text: String },
}

/// Everything needed to draw one picture.
#[derive(Debug, Clone, PartialEq)]
pub struct RenderPlan {
    pub scene: Scene,
    pub outfit: Outfit,
    pub layers: Vec<Layer>,
}

pub fn plan(conditions: &Conditions, style: Style) -> RenderPlan {
    let scene = Scene::from_conditions(conditions);
    let outfit = dress(conditions, &scene);
    let layers = style.layers(&scene, &outfit);
    RenderPlan {
        scene,
        outfit,
        layers,
    }
}
