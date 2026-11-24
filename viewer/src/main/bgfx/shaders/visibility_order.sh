#define ORDER_BLOCK_CANDIDATES 1024u
#define ORDER_BLOCK_WORDS 32u
#define ORDER_THREAD_CANDIDATES 16u

uint countOrderBits(uint value)
{
    value = value - ((value >> 1u) & 0x55555555u);
    value = (value & 0x33333333u) + ((value >> 2u) & 0x33333333u);
    value = (value + (value >> 4u)) & 0x0F0F0F0Fu;
    return (value * 0x01010101u) >> 24u;
}
