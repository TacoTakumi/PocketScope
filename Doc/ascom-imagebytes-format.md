# ASCOM Alpaca ImageBytes Binary Format

Definitive reference derived from the official ASCOM Alpaca Simulator source code
(`3rdParty/ASCOM.Alpaca.Simulators/Camera.Simulator/Camera.cs`).

## Header (44 bytes, 11 x Int32 little-endian)

| Offset | Field                  | Value                          |
|--------|------------------------|--------------------------------|
| 0      | MetadataVersion        | 1                              |
| 4      | ErrorNumber            | 0 (success)                    |
| 8      | ClientTransactionID    | from request                   |
| 12     | ServerTransactionID    | server-assigned                |
| 16     | DataStart              | 44                             |
| 20     | ImageElementType       | 2 = Int32                      |
| 24     | TransmissionElementType| 2 = Int32                      |
| 28     | Rank                   | 2 (mono/Bayer) or 3 (color)   |
| 32     | Dimension1             | **NumX = width (columns)**     |
| 36     | Dimension2             | **NumY = height (rows)**       |
| 40     | Dimension3             | 0 (mono) or NumPlanes (color)  |

## Pixel Data (starts at offset 44)

### Array Convention

The ASCOM simulator stores image data as:

```csharp
int[,] imageArray = new int[numX, numY];  // [width, height]
imageArray[x, y] = pixelValue;            // x = column, y = row
```

### Binary Serialization Order (Column-Major)

`ToByteArray()` serializes the C# array sequentially from memory. C# multidimensional
arrays are row-major *in memory*, meaning the last index varies fastest. For `int[numX, numY]`,
the Y index (row) varies fastest:

```
P(0,0), P(0,1), P(0,2), ..., P(0,H-1),   // all rows of column 0
P(1,0), P(1,1), P(1,2), ..., P(1,H-1),   // all rows of column 1
...
P(W-1,0), P(W-1,1), ..., P(W-1,H-1)      // all rows of last column
```

This is **column-major order** from an image perspective: iterate all rows for column 0,
then all rows for column 1, etc.

### Worked Example (3 wide x 2 tall)

Image layout (row, col):
```
[A B C]   row 0
[D E F]   row 1
```

Binary byte order: `A, D, B, E, C, F`

i.e., column 0 (A, D), column 1 (B, E), column 2 (C, F).

### Converting from Row-Major Source (e.g., Camera2)

Camera2 RAW_SENSOR delivers pixels row-major: `A, B, C, D, E, F`.

To produce the ASCOM column-major order:

```kotlin
for (x in 0 until width) {        // outer: columns
    for (y in 0 until height) {    // inner: rows
        val srcIdx = (y * width + x) * 2  // row-major source index
        // read 16-bit LE pixel at srcIdx, write as 32-bit LE
    }
}
```

## JSON ImageArray Format (also Column-Major)

The JSON `/imagearray` endpoint returns the same column-major layout as a jagged array.
The outer array has `NumX` (width) elements, each inner array has `NumY` (height) elements:

```json
[
  [P(0,0), P(0,1), ..., P(0,H-1)],
  [P(1,0), P(1,1), ..., P(1,H-1)],
  ...
  [P(W-1,0), P(W-1,1), ..., P(W-1,H-1)]
]
```

The JSON response wrapper includes `Type` (2 = Int32) and `Rank` (2 = 2D mono).

## Source References

- Array declaration: `Camera.cs:1832` — `imageArray = new int[numX, numY]`
- Fill loop: `Camera.cs:2924-2945` — `imageArray[x, y] = pixelProcess(...)`
- JSON conversion: `CameraController.cs:1174-1188` — `To2DJArray()` iterates dim0 (X) outer
- Binary conversion: `CameraController.cs:896` — `((Array)...ImageArray).ToByteArray(...)`
- Dimension docs: `CameraController.cs:846` — "NumX = 7, NumY = 5... column-major order"
