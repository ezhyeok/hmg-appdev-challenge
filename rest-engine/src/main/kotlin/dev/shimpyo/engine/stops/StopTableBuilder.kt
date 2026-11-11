package dev.shimpyo.engine.stops

import java.io.File

/** 데이터 폴더의 JSON(tools/data가 만든 것)을 [StopRows] 규칙으로 변환한 행들. [StopFile] 순서를 지킨다 */
internal fun loadRecords(dataDir: File): List<StopRecord> {
    val out = ArrayList<StopRecord>(70_000)
    for (file in StopFile.entries) {
        File(dataDir, file.fileName).reader(Charsets.UTF_8).use { r ->
            FlatJson.forEachObject(r) { row -> StopRows.map(file, row)?.let(out::add) }
        }
    }
    return out
}

/** 고속도로 차로 선형 (motorways.json, tools/data/build.py가 OSM에서 단순화한 것) */
internal fun loadLines(dataDir: File): List<MotorwayLine> {
    val out = ArrayList<MotorwayLine>(25_000)
    File(dataDir, "motorways.json").reader(Charsets.UTF_8).use { r ->
        FlatJson.forEachObject(r) { row ->
            out += MotorwayLine(row["r"] as String, (row["ow"] as Double) == 0.0, row["p"] as DoubleArray)
        }
    }
    return out
}

/**
 * stops.bin 만들기. 실행: `./gradlew -p rest-engine buildStopTable`
 * (tools/data/build.py로 JSON을 다시 만들었으면 이것도 다시 실행한다. StopTableTest가 둘이 어긋나면 실패한다)
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: StopTableBuilder <assets/data dir> <out stops.bin>" }
    val t0 = System.nanoTime()
    val records = loadRecords(File(args[0]))
    val lines = loadLines(File(args[0]))
    val bytes = StopTable.encode(records, lines)
    File(args[1]).writeBytes(bytes)
    val byKind = records.groupingBy { it.candidate.kind }.eachCount()
    println("stops.bin: ${records.size} rows, ${lines.size} motorway lines (${lines.sumOf { it.points.size / 2 }} points), " +
        "${bytes.size} bytes, $byKind (${(System.nanoTime() - t0) / 1_000_000} ms)")
}
