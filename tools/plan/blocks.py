"""Content blocks shared by the docx and Markdown renderers.

Inline markup inside any text: **bold**, `code`, {{accent bold}}, [[placeholder]].
"""
from dataclasses import dataclass, field
from typing import List, Optional, Tuple


@dataclass
class H:
    """Sub-heading inside a box."""
    text: str


@dataclass
class P:
    text: str
    size: float = 9.5
    color: Optional[str] = None
    align: Optional[str] = None      # 'center' | 'right'
    after: int = 80
    before: int = 0


@dataclass
class Bullets:
    items: List[str]
    size: float = 9.5


@dataclass
class Table:
    header: List[str]
    rows: List[List[str]]
    widths: List[float]               # relative weights
    size: float = 8.5
    first_col_bold: bool = True
    align: Optional[List[str]] = None  # per column: 'left' | 'center' | 'right'
    highlight_rows: List[int] = field(default_factory=list)


@dataclass
class Callout:
    lines: List[str]
    title: Optional[str] = None
    tone: str = "accent"              # 'accent' | 'neutral' | 'warn'
    size: float = 9.5
    bullets: bool = False


@dataclass
class KeyNumbers:
    items: List[Tuple[str, str, str]]  # (value, label, source)


@dataclass
class Figure:
    key: str                          # resolved by the build script to a PNG path
    caption: str
    width_cm: float = 17.0
    alt: str = ""
    caption_screens: Optional[str] = None   # used instead of caption when real screenshots replace a mockup


@dataclass
class Storyboard:
    title: str
    subtitle: str
    steps: List[Tuple[str, str, str]]  # (step, what the car/app does, what the user hears/sees)


@dataclass
class Note:
    text: str


@dataclass
class Spacer:
    pt: float = 6


@dataclass
class Section:
    number: int
    tag: str                          # content-control tag in the official template
    title: str
    blocks: list
