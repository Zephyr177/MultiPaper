package org.purpurmc.purpur.gui;

import com.google.common.collect.Sets;
import javax.swing.UIManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.util.Iterator;
import java.util.Set;

public class JColorTextPane extends JTextPane {
    private static final GUIColor DEFAULT_COLOR;
    static {
        DEFAULT_COLOR = UIManager.getSystemLookAndFeelClassName().equals("com.sun.java.swing.plaf.gtk.GTKLookAndFeel")
            ? GUIColor.WHITE : GUIColor.BLACK;
    }


    public void append(String msg) {
        // TODO: update to use adventure instead
        BaseComponent[] components = TextComponent.fromLegacyText(DEFAULT_COLOR.getCode() + msg, DEFAULT_COLOR.getChatColor());
        for (BaseComponent component : components) {
            String text = component.toPlainText();
            if (text == null || text.isEmpty()) {
                continue;
            }

            GUIColor guiColor = GUIColor.getColor(component.getColor());

            StyleContext context = StyleContext.getDefaultStyleContext();
            AttributeSet attr = context.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, guiColor.getColor());
            attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Bold, component.isBold() || guiColor != DEFAULT_COLOR);
            attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Italic, component.isItalic());
            attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Underline, component.isUnderlined());
            attr = context.addAttribute(attr, StyleConstants.CharacterConstants.StrikeThrough, component.isStrikethrough());
            //attr = context.addAttribute(attr, StyleConstants.CharacterConstants.Blink, component.isObfuscated()); // no such thing as Blink, sadly

            try {
                int pos = getDocument().getLength();
                getDocument().insertString(pos, text, attr);

                if (component.isObfuscated()) {
                    // dirty hack to blink some text
                    Blink blink = new Blink(pos, text.length(), attr, context.addAttribute(attr, StyleConstants.Foreground, getBackground()));
                    BLINKS.add(blink);
                }
            } catch (BadLocationException ignore) {
            }
        }
    }

    private static final Set<Blink> BLINKS = Sets.newHashSet();
    private static boolean SYNC_BLINK;

    static {
        new Timer(500, e -> {
            SYNC_BLINK = !SYNC_BLINK;
            // Remove stale blinks whose text pane has been disposed, then blink the rest.
            Iterator<Blink> it = BLINKS.iterator();
            while (it.hasNext()) {
                Blink blink = it.next();
                if (blink.isStale()) {
                    it.remove();
                }
            }
            BLINKS.forEach(Blink::blink);
        }).start();
    }

    /**
     * Removes all {@link Blink} entries that belong to this text pane.
     * Call when the pane is no longer in use so the Timer does not keep it alive.
     */
    public void dispose() {
        BLINKS.removeIf(blink -> blink.getPane() == this);
    }

    public class Blink {
        private final int start, length;
        private final AttributeSet attr1, attr2;

        private Blink(int start, int length, AttributeSet attr1, AttributeSet attr2) {
            this.start = start;
            this.length = length;
            this.attr1 = attr1;
            this.attr2 = attr2;
        }

        /**
         * @return the owning {@link JColorTextPane}.
         */
        public JColorTextPane getPane() {
            return JColorTextPane.this;
        }

        /**
         * @return {@code true} if the owning text pane is no longer valid (no document).
         */
        public boolean isStale() {
            return getPane().getDocument() == null;
        }

        private void blink() {
            if (isStale()) {
                return;
            }
            try {
                getStyledDocument().setCharacterAttributes(start, length, SYNC_BLINK ? attr1 : attr2, true);
            } catch (Exception ignored) {
                // Pane disposed or document no longer available; entry will be cleaned up by the Timer.
            }
        }
    }
}
