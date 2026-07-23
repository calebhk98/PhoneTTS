package com.phonetts.core.convert

/**
 * `TensorProto.DataType` enum values from onnx.proto - the numeric type of an initializer's or
 * a value's elements. Only the ones a weights transcoder is likely to emit are listed.
 */
object OnnxDataType {
    const val FLOAT = 1
    const val UINT8 = 2
    const val INT8 = 3
    const val INT16 = 5
    const val INT32 = 6
    const val INT64 = 7
    const val BOOL = 9
    const val FLOAT16 = 10
}

/**
 * `AttributeProto.AttributeType` enum values from onnx.proto - the discriminator that tells a
 * reader which of the AttributeProto payload fields is populated.
 */
object OnnxAttributeType {
    const val FLOAT = 1
    const val INT = 2
    const val STRING = 3
    const val TENSOR = 4
    const val FLOATS = 6
    const val INTS = 7
    const val STRINGS = 8
}

/**
 * Hand-emits the ONNX protobuf messages needed to assemble a graph, using only [ProtoBuffer].
 * This is deliberately NOT the whole ONNX op set - just the message envelopes with the correct
 * field numbers and wire types per onnx.proto, so a per-architecture recipe can author a graph's
 * initializers, inputs, outputs, and nodes and have a stock ONNX Runtime load the result.
 *
 * Every builder returns a fully-encoded message as a [ByteArray]; nested messages are passed in
 * pre-encoded (e.g. [graph] takes already-built node/initializer/value-info byte arrays), which
 * keeps each builder tiny and lets callers compose graphs bottom-up. The field numbers below are
 * quoted straight from onnx.proto and documented per builder.
 */
object OnnxProto {
    // TensorProto: dims=1, data_type=2, name=8, raw_data=9.
    private const val TENSOR_DIMS = 1
    private const val TENSOR_DATA_TYPE = 2
    private const val TENSOR_NAME = 8
    private const val TENSOR_RAW_DATA = 9

    // ValueInfoProto: name=1, type=2.
    private const val VALUEINFO_NAME = 1
    private const val VALUEINFO_TYPE = 2

    // TypeProto: tensor_type=1. TypeProto.Tensor: elem_type=1, shape=2.
    private const val TYPE_TENSOR_TYPE = 1
    private const val TENSORTYPE_ELEM = 1
    private const val TENSORTYPE_SHAPE = 2

    // TensorShapeProto: dim=1. TensorShapeProto.Dimension: dim_value=1, dim_param=2.
    private const val SHAPE_DIM = 1
    private const val DIM_VALUE = 1
    private const val DIM_PARAM = 2

    // NodeProto: input=1, output=2, name=3, op_type=4, attribute=5.
    private const val NODE_INPUT = 1
    private const val NODE_OUTPUT = 2
    private const val NODE_NAME = 3
    private const val NODE_OP_TYPE = 4
    private const val NODE_ATTRIBUTE = 5

    // AttributeProto: name=1, f=2, i=3, s=4, t=5, floats=7, ints=8, strings=9, type=20.
    private const val ATTR_NAME = 1
    private const val ATTR_F = 2
    private const val ATTR_I = 3
    private const val ATTR_S = 4
    private const val ATTR_T = 5
    private const val ATTR_FLOATS = 7
    private const val ATTR_INTS = 8
    private const val ATTR_STRINGS = 9
    private const val ATTR_TYPE = 20

    // GraphProto: node=1, name=2, initializer=5, input=11, output=12.
    private const val GRAPH_NODE = 1
    private const val GRAPH_NAME = 2
    private const val GRAPH_INITIALIZER = 5
    private const val GRAPH_INPUT = 11
    private const val GRAPH_OUTPUT = 12

    // ModelProto: ir_version=1, producer_name=2, graph=7, opset_import=8.
    private const val MODEL_IR_VERSION = 1
    private const val MODEL_PRODUCER_NAME = 2
    private const val MODEL_GRAPH = 7
    private const val MODEL_OPSET_IMPORT = 8

    // OperatorSetIdProto: domain=1, version=2. An empty domain means the default ONNX domain.
    private const val OPSET_VERSION = 2

    /** A TensorProto initializer/constant carrying [rawData] little-endian bytes of type [dataType]. */
    fun tensorProto(
        name: String,
        dataType: Int,
        dims: List<Long>,
        rawData: ByteArray,
    ): ByteArray =
        ProtoBuffer()
            .repeatedInt64(TENSOR_DIMS, dims)
            .int32(TENSOR_DATA_TYPE, dataType)
            .string(TENSOR_NAME, name)
            .bytes(TENSOR_RAW_DATA, rawData)
            .toByteArray()

    /**
     * A ValueInfoProto naming a graph input/output of element type [elemType] and shape [dims].
     * A non-null dim is a fixed extent; a null dim is a symbolic (dynamic) dimension named by index.
     */
    fun valueInfo(
        name: String,
        elemType: Int,
        dims: List<Long?>,
    ): ByteArray {
        val tensorType =
            ProtoBuffer()
                .int32(TENSORTYPE_ELEM, elemType)
                .message(TENSORTYPE_SHAPE, shapeProto(dims))
                .toByteArray()
        val type = ProtoBuffer().message(TYPE_TENSOR_TYPE, tensorType).toByteArray()
        return ProtoBuffer().string(VALUEINFO_NAME, name).message(VALUEINFO_TYPE, type).toByteArray()
    }

    /** A NodeProto for op [opType] with [inputs] -> [outputs], a [name], and pre-encoded [attributes]. */
    fun node(
        opType: String,
        inputs: List<String>,
        outputs: List<String>,
        name: String,
        attributes: List<ByteArray> = emptyList(),
    ): ByteArray {
        val builder = ProtoBuffer()
        for (input in inputs) builder.string(NODE_INPUT, input)
        for (output in outputs) builder.string(NODE_OUTPUT, output)
        builder.string(NODE_NAME, name).string(NODE_OP_TYPE, opType)
        for (attr in attributes) builder.message(NODE_ATTRIBUTE, attr)
        return builder.toByteArray()
    }

    /** A GraphProto composed from pre-encoded nodes, initializers, inputs, and outputs. */
    fun graph(
        name: String,
        nodes: List<ByteArray>,
        initializers: List<ByteArray>,
        inputs: List<ByteArray>,
        outputs: List<ByteArray>,
    ): ByteArray {
        val builder = ProtoBuffer()
        for (node in nodes) builder.message(GRAPH_NODE, node)
        builder.string(GRAPH_NAME, name)
        for (init in initializers) builder.message(GRAPH_INITIALIZER, init)
        for (input in inputs) builder.message(GRAPH_INPUT, input)
        for (output in outputs) builder.message(GRAPH_OUTPUT, output)
        return builder.toByteArray()
    }

    /** A ModelProto wrapping [graph] with an [irVersion], [producer] name, and one opset [opsetVersion]. */
    fun model(
        graph: ByteArray,
        opsetVersion: Long,
        producer: String,
        irVersion: Long,
    ): ByteArray {
        val opset = ProtoBuffer().varint(OPSET_VERSION, opsetVersion).toByteArray()
        return ProtoBuffer()
            .varint(MODEL_IR_VERSION, irVersion)
            .string(MODEL_PRODUCER_NAME, producer)
            .message(MODEL_GRAPH, graph)
            .message(MODEL_OPSET_IMPORT, opset)
            .toByteArray()
    }

    fun attributeInt(
        name: String,
        value: Long,
    ): ByteArray =
        ProtoBuffer()
            .string(ATTR_NAME, name)
            .varint(ATTR_I, value)
            .int32(ATTR_TYPE, OnnxAttributeType.INT)
            .toByteArray()

    fun attributeFloat(
        name: String,
        value: Float,
    ): ByteArray =
        ProtoBuffer()
            .string(ATTR_NAME, name)
            .float(ATTR_F, value)
            .int32(ATTR_TYPE, OnnxAttributeType.FLOAT)
            .toByteArray()

    fun attributeString(
        name: String,
        value: String,
    ): ByteArray =
        ProtoBuffer()
            .string(ATTR_NAME, name)
            .string(ATTR_S, value)
            .int32(ATTR_TYPE, OnnxAttributeType.STRING)
            .toByteArray()

    fun attributeInts(
        name: String,
        values: List<Long>,
    ): ByteArray {
        val builder = ProtoBuffer().string(ATTR_NAME, name)
        for (v in values) builder.varint(ATTR_INTS, v)
        return builder.int32(ATTR_TYPE, OnnxAttributeType.INTS).toByteArray()
    }

    fun attributeFloats(
        name: String,
        values: FloatArray,
    ): ByteArray {
        val builder = ProtoBuffer().string(ATTR_NAME, name)
        for (v in values) builder.float(ATTR_FLOATS, v)
        return builder.int32(ATTR_TYPE, OnnxAttributeType.FLOATS).toByteArray()
    }

    fun attributeStrings(
        name: String,
        values: List<String>,
    ): ByteArray {
        val builder = ProtoBuffer().string(ATTR_NAME, name)
        for (v in values) builder.string(ATTR_STRINGS, v)
        return builder.int32(ATTR_TYPE, OnnxAttributeType.STRINGS).toByteArray()
    }

    fun attributeTensor(
        name: String,
        tensor: ByteArray,
    ): ByteArray =
        ProtoBuffer()
            .string(ATTR_NAME, name)
            .message(ATTR_T, tensor)
            .int32(ATTR_TYPE, OnnxAttributeType.TENSOR)
            .toByteArray()

    private fun shapeProto(dims: List<Long?>): ByteArray {
        val builder = ProtoBuffer()
        for ((index, extent) in dims.withIndex()) {
            builder.message(SHAPE_DIM, dimension(index, extent))
        }
        return builder.toByteArray()
    }

    private fun dimension(
        index: Int,
        extent: Long?,
    ): ByteArray {
        val dim = ProtoBuffer()
        if (extent == null) return dim.string(DIM_PARAM, "d$index").toByteArray()
        return dim.varint(DIM_VALUE, extent).toByteArray()
    }
}
