package com.example.voicerecorder;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TranscriberOrderTest {

    @Test
    public void chunkOrder_sortsNumericallyBeyond99Parts() {
        List<String> names = Arrays.asList(
                "Rec_A__part100_last.mp3",
                "Rec_A__part02.mp3",
                "Rec_A__part10.mp3",
                "Rec_A__part99.mp3",
                "Rec_A__part01.mp3",
                "Rec_B__part01_last.m4a"
        );
        Collections.sort(names, Transcriber.CHUNK_NAME_ORDER);
        assertEquals(Arrays.asList(
                "Rec_A__part01.mp3",
                "Rec_A__part02.mp3",
                "Rec_A__part10.mp3",
                "Rec_A__part99.mp3",
                "Rec_A__part100_last.mp3",
                "Rec_B__part01_last.m4a"
        ), names);
    }

    @Test
    public void namePattern_matchesMp3AndM4aParts() {
        assertTrue(Transcriber.NAME.matcher("Recording_1__part01.mp3").matches());
        assertTrue(Transcriber.NAME.matcher("Recording_1__part12_last.m4a").matches());
    }
}
