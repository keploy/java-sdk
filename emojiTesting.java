import com.vdurmont.emoji.Emoji;
import com.vdurmont.emoji.EmojiManager;
import com.vdurmont.emoji.EmojiParser;

import java.util.Collection;

public class emojiTesting {

    public static void main(String[] args) {

        Emoji warn = EmojiManager.getForAlias("warning");

        System.out.println(warn.getUnicode());
        System.out.println("\ufe0f");
    }
}
