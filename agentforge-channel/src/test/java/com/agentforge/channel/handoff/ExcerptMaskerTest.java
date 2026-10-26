package com.agentforge.channel.handoff;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExcerptMaskerTest {

    @Test
    void phoneIdCardAndBankCardNumbersAreMasked() {
        assertEquals("我的手机138****5678，回电", ExcerptMasker.excerpt("我的手机13812345678，回电", 120));
        assertEquals("身份证110***********234X", ExcerptMasker.excerpt("身份证11010519900101234X", 120));
        assertEquals("卡号622*********0123", ExcerptMasker.excerpt("卡号6222021234560123", 120));
        // Short numbers are amounts, dates or order lines: kept.
        assertEquals("退 300 元，订单 2026102501", ExcerptMasker.excerpt("退 300 元，订单 2026102501", 120));
        assertEquals("10-26 14:03 来电", ExcerptMasker.excerpt("10-26 14:03 来电", 120));
    }

    /** Found in review: how people actually type them. */
    @Test
    void groupedAndFullWidthNumbersAreMaskedToo() {
        assertEquals("电话138****5678吧", ExcerptMasker.excerpt("电话138 1234 5678吧", 120));
        assertEquals("电话138****5678", ExcerptMasker.excerpt("电话138-1234-5678", 120));
        assertEquals("卡号 622*********7890", ExcerptMasker.excerpt("卡号 6222 0212 3456 7890", 120));
        assertEquals("手机１３８****５６７８", ExcerptMasker.excerpt("手机１３８１２３４５６７８", 120));
    }

    @Test
    void cutByCharactersNeverHalfASurrogatePair() {
        assertEquals("一二三…", ExcerptMasker.excerpt("一二三四五", 3));
        assertEquals("ab😀…", ExcerptMasker.excerpt("ab😀😀😀", 3));
        assertEquals("短", ExcerptMasker.excerpt("  短  ", 3));
    }

    @Test
    void zeroMeansNoExcerpt() {
        assertNull(ExcerptMasker.excerpt("我要投诉", 0));
    }
}
