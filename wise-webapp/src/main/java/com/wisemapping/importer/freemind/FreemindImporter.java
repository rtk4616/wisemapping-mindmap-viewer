/*
*    Copyright [2011] [wisemapping]
*
*   Licensed under WiseMapping Public License, Version 1.0 (the "License").
*   It is basically the Apache License, Version 2.0 (the "License") plus the
*   "powered by wisemapping" text requirement on every single page;
*   you may not use this file except in compliance with the License.
*   You may obtain a copy of the license at
*
*       http://www.wisemapping.org/license
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*   See the License for the specific language governing permissions and
*   limitations under the License.
*/

package com.wisemapping.importer.freemind;

import com.sun.org.apache.xerces.internal.dom.TextImpl;
import com.wisemapping.importer.Importer;
import com.wisemapping.importer.ImporterException;
import com.wisemapping.model.MindMap;
import com.wisemapping.model.ShapeStyle;
import com.wisemapping.model.MindMapNative;
import com.wisemapping.util.JAXBUtils;
import com.wisemapping.xml.freemind.*;
import com.wisemapping.xml.freemind.Map;
import com.wisemapping.xml.freemind.Node;
import com.wisemapping.xml.mindmap.RelationshipType;
import com.wisemapping.xml.mindmap.TopicType;
import com.wisemapping.xml.mindmap.Link;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.*;

import javax.xml.bind.JAXBException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.math.BigInteger;

public class FreemindImporter
        implements Importer {

    private com.wisemapping.xml.mindmap.ObjectFactory mindmapObjectFactory;
    private static final String POSITION_LEFT = "left";
    private static final String BOLD = "bold";
    private static final String ITALIC = "italic";
    private static final String EMPTY_NOTE = "";
    private java.util.Map<String, TopicType> nodesMap = null;
    private List<RelationshipType> relationships = null;
    private static final String EMPTY_FONT_STYLE = ";;;;;";

    private int currentId;

    public MindMap importMap(String mapName, String description, InputStream input) throws ImporterException {

        final MindMap map;
        mindmapObjectFactory = new com.wisemapping.xml.mindmap.ObjectFactory();
        try {
            final Map freemindMap = (Map) JAXBUtils.getMapObject(input, "com.wisemapping.xml.freemind");

            final com.wisemapping.xml.mindmap.Map mindmapMap = mindmapObjectFactory.createMap();
            mindmapMap.setVersion("pela");
            currentId = 0;

            final Node centralNode = freemindMap.getNode();
            final TopicType centralTopic = mindmapObjectFactory.createTopicType();
            centralTopic.setId(String.valueOf(currentId++));
            centralTopic.setCentral(true);

            setNodePropertiesToTopic(centralTopic, centralNode);
            centralTopic.setShape(ShapeStyle.ELIPSE.getStyle());
            mindmapMap.getTopic().add(centralTopic);
            mindmapMap.setName(mapName);

            nodesMap = new HashMap<String, TopicType>();
            relationships = new ArrayList<RelationshipType>();
            nodesMap.put(centralNode.getID(), centralTopic);
            addTopicFromNode(centralNode, centralTopic);
            fixCentralTopicChildOrder(centralTopic);

            addRelationships(mindmapMap);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JAXBUtils.saveMap(mindmapMap, out, "com.wisemapping.xml.mindmap");

            map = new MindMap();
            map.setNativeXml(new String(out.toByteArray(), Charset.forName("UTF-8")));
            map.setTitle(mapName);
            map.setDescription(description);
            map.setNativeBrowser(new MindMapNative());

        } catch (JAXBException e) {
            throw new ImporterException(e);
        } catch (IOException e) {
            throw new ImporterException(e);
        }

        return map;
    }

    private void addRelationships(com.wisemapping.xml.mindmap.Map mindmapMap) {
        List<RelationshipType> mapRelationships = mindmapMap.getRelationship();
        for (RelationshipType relationship : relationships) {
            relationship.setId(String.valueOf(currentId++));

            fixRelationshipControlPoints(relationship);

            //Fix dest ID
            String destId = relationship.getDestTopicId();
            TopicType destTopic = nodesMap.get(destId);
            relationship.setDestTopicId(destTopic.getId());
            //Fix src ID
            String srcId = relationship.getSrcTopicId();
            TopicType srcTopic = nodesMap.get(srcId);
            relationship.setSrcTopicId(srcTopic.getId());

            mapRelationships.add(relationship);
        }
    }

    private void fixRelationshipControlPoints(RelationshipType relationship) {
        //Both relationship node's ids should be freemind ones at this point.
        TopicType srcTopic = nodesMap.get(relationship.getSrcTopicId());
        TopicType destTopicType = nodesMap.get(relationship.getDestTopicId());

        //Fix x coord
        if (isOnLeftSide(srcTopic)) {
            String[] srcCtrlPoint = relationship.getSrcCtrlPoint().split(",");
            int x = Integer.valueOf(srcCtrlPoint[0]) * -1;
            relationship.setSrcCtrlPoint(x + "," + srcCtrlPoint[1]);
        }
        if (isOnLeftSide(destTopicType)) {
            String[] destCtrlPoint = relationship.getDestCtrlPoint().split(",");
            int x = Integer.valueOf(destCtrlPoint[0]) * -1;
            relationship.setDestCtrlPoint(x + "," + destCtrlPoint[1]);
        }

        //Fix y coord
        if (srcTopic.getOrder() % 2 != 0) { //Odd order.
            String[] srcCtrlPoint = relationship.getSrcCtrlPoint().split(",");
            int y = Integer.valueOf(srcCtrlPoint[1]) * -1;
            relationship.setSrcCtrlPoint(srcCtrlPoint[0] + "," + y);
        }
        if (destTopicType.getOrder() % 2 != 0) { //Odd order.
            String[] destCtrlPoint = relationship.getDestCtrlPoint().split(",");
            int y = Integer.valueOf(destCtrlPoint[1]) * -1;
            relationship.setDestCtrlPoint(destCtrlPoint[0] + "," + y);
        }

    }

    private void fixCentralTopicChildOrder(TopicType centralTopic) {
        List<TopicType> topics = centralTopic.getTopic();
        List<TopicType> leftTopics = new ArrayList<TopicType>();
        List<TopicType> rightTopics = new ArrayList<TopicType>();

        for (TopicType topic : topics) {
            if (isOnLeftSide(topic)) {
                leftTopics.add(topic);
            } else {
                rightTopics.add(topic);
            }
        }

        if (leftTopics.size() > 0) {
            int size = leftTopics.size();
            int index = 0;
            int center = size / 2;
            if (size % 2 == 0) { //Even number, then place middle point in 1 index
                index = 1;
                center--;
            }
            int index2 = index;

            leftTopics.get(center).setOrder(index);
            for (int i = center - 1; i >= 0; i--) {
                if (index == 0) {
                    index++;
                } else {
                    index += 2;
                }
                leftTopics.get(i).setOrder(index);
            }
            index = index2;
            for (int i = center + 1; i < size; i++) {
                if (index == 1) {
                    index++;
                } else {
                    index += 2;
                }
                leftTopics.get(i).setOrder(index);
            }
        }
        if (rightTopics.size() > 0) {
            int size = rightTopics.size();
            int index = 0;
            int center = size / 2;
            if (size % 2 == 0) { //Even number, then place middle point in 1 index
                index = 1;
                center--;
            }
            int index2 = index;
            rightTopics.get(center).setOrder(index);
            for (int i = center - 1; i >= 0; i--) {
                if (index == 0) {
                    index++;
                } else {
                    index += 2;
                }
                rightTopics.get(i).setOrder(index);
            }
            index = index2;
            for (int i = center + 1; i < size; i++) {
                if (index == 1) {
                    index++;
                } else {
                    index += 2;
                }
                rightTopics.get(i).setOrder(index);
            }
        }
    }

    private boolean isOnLeftSide(TopicType topic) {
        String[] position = topic.getPosition().split(",");
        int x = Integer.parseInt(position[0]);
        return x < 0;
    }

    private void addTopicFromNode(@NotNull Node mainNode, @NotNull TopicType topic) {
        final List<Object> freemindNodes = mainNode.getArrowlinkOrCloudOrEdge();
        TopicType currentTopic = topic;
        int order = 0;
        for (Object freemindNode : freemindNodes) {

            if (freemindNode instanceof Node) {
                final Node node = (Node) freemindNode;
                TopicType newTopic = mindmapObjectFactory.createTopicType();
                newTopic.setId(String.valueOf(currentId++));
                nodesMap.put(node.getID(), newTopic);  //Lets use freemind id temporarily. This will be fixed when adding relationship to the map.
                newTopic.setOrder(order++);

                // Is there any link ?
                final String url = node.getLINK();
                if (url != null) {
                    final Link link = new Link();
                    link.setUrl(url);
                    newTopic.setLink(link);
                }

                if (POSITION_LEFT.equals(mainNode.getPOSITION())) {
                    node.setPOSITION(POSITION_LEFT);
                }

                setNodePropertiesToTopic(newTopic, node);
                addTopicFromNode(node, newTopic);
                if (!newTopic.equals(topic)) {
                    topic.getTopic().add(newTopic);
                }
                currentTopic = newTopic;

            } else if (freemindNode instanceof Font) {
                final Font font = (Font) freemindNode;
                final String fontStyle = generateFontStyle(mainNode, font);
                if (fontStyle != null) {
                    currentTopic.setFontStyle(fontStyle);
                }
            } else if (freemindNode instanceof Edge) {
                final Edge edge = (Edge) freemindNode;
                currentTopic.setBrColor(edge.getCOLOR());
            } else if (freemindNode instanceof Icon) {
                final Icon freemindIcon = (Icon) freemindNode;

                String iconId = freemindIcon.getBUILTIN();
                final String wiseIconId = FreemindIconConverter.toWiseId(iconId);
                if (wiseIconId != null) {
                    final com.wisemapping.xml.mindmap.Icon mindmapIcon = new com.wisemapping.xml.mindmap.Icon();
                    mindmapIcon.setId(wiseIconId);
                    currentTopic.getIcon().add(mindmapIcon);
                }

            } else if (freemindNode instanceof Hook) {
                final Hook hook = (Hook) freemindNode;
                final com.wisemapping.xml.mindmap.Note mindmapNote = new com.wisemapping.xml.mindmap.Note();
                String textNote = hook.getText();
                if (textNote == null) // It is not a note is a BlinkingNodeHook or AutomaticLayout Hook
                {
                    textNote = EMPTY_NOTE;
                    mindmapNote.setText(textNote);
                    currentTopic.setNote(mindmapNote);
                }
            } else if (freemindNode instanceof Richcontent) {
                final Richcontent content = (Richcontent) freemindNode;
                final String type = content.getTYPE();

                if (type.equals("NODE")) {
                    String text = getText(content);
                    text = text.replaceAll("\n", "");
                    text = text.trim();
                    currentTopic.setText(text);
                } else {
                    String text = getRichContent(content);
                    final com.wisemapping.xml.mindmap.Note mindmapNote = new com.wisemapping.xml.mindmap.Note();
                    text = text != null ? text.replaceAll("\n", "%0A") : EMPTY_NOTE;
                    mindmapNote.setText(text);
                    currentTopic.setNote(mindmapNote);

                }
            } else if (freemindNode instanceof Arrowlink) {
                final Arrowlink arrow = (Arrowlink) freemindNode;
                RelationshipType relationship = mindmapObjectFactory.createRelationshipType();
                String destId = arrow.getDESTINATION();
                relationship.setSrcTopicId(mainNode.getID());
                relationship.setDestTopicId(destId);
                String[] inclination = arrow.getENDINCLINATION().split(";");
                relationship.setDestCtrlPoint(inclination[0] + "," + inclination[1]);
                inclination = arrow.getSTARTINCLINATION().split(";");
                relationship.setSrcCtrlPoint(inclination[0] + "," + inclination[1]);
                //relationship.setCtrlPointRelative(true);
                relationship.setEndArrow(!arrow.getENDARROW().toLowerCase().equals("none"));
                relationship.setStartArrow(!arrow.getSTARTARROW().toLowerCase().equals("none"));
                relationship.setLineType("3");
                relationships.add(relationship);
            }
        }
    }

    private String getRichContent(Richcontent content) {
        String result = null;
        List<Element> elementList = content.getHtml().getAny();

        Element body = null;
        for (Element elem : elementList) {
            if (elem.getNodeName().equals("body")) {
                body = elem;
                break;
            }
        }
        if (body != null) {
            result = body.getTextContent();
        }
        return result;
    }

    private String getText(Richcontent content) {
        String result = "";
        List<Element> elementList = content.getHtml().getAny();

        Element body = null;
        for (Element elem : elementList) {
            if (elem.getNodeName().equals("body")) {
                body = elem;
                break;
            }
        }
        if (body != null) {
            String textNode = buildTextFromChildren(body);
            if (textNode != null)
                result = textNode.trim();

        }
        return result;
    }

    private String buildTextFromChildren(org.w3c.dom.Node body) {
        StringBuilder text = new StringBuilder();
        NodeList childNodes = body.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            org.w3c.dom.Node child = childNodes.item(i);
            if (child instanceof TextImpl) {
                text.append(" ");
                text.append(child.getTextContent());
            } else {
                String textElem = buildTextFromChildren(child);
                if (textElem != null && !textElem.equals("")) {
                    text.append(textElem);
                }
            }
        }
        return text.toString();
    }

    private void setNodePropertiesToTopic(@NotNull com.wisemapping.xml.mindmap.TopicType wiseTopic, @NotNull com.wisemapping.xml.freemind.Node freemindNode) {
        wiseTopic.setText(freemindNode.getTEXT());
        wiseTopic.setBgColor(freemindNode.getBACKGROUNDCOLOR());

        final String shape = getShapeFormFromNode(freemindNode);
        wiseTopic.setShape(shape);
        int pos = 1;
        if (POSITION_LEFT.equals(freemindNode.getPOSITION())) {
            pos = -1;
        }
        Integer orderPosition = wiseTopic.getOrder() != null ? wiseTopic.getOrder() : 0;
        int position = pos * 200 + (orderPosition + 1) * 10;

        wiseTopic.setPosition(position + "," + 200 * orderPosition);
        final String fontStyle = generateFontStyle(freemindNode, null);
        if (fontStyle != null) {
            wiseTopic.setFontStyle(fontStyle);
        }

        // Is there any link ?
        final String url = freemindNode.getLINK();
        if (url != null) {
            final Link link = new Link();
            link.setUrl(url);
            wiseTopic.setLink(link);
        }

        final Boolean folded = Boolean.valueOf(freemindNode.getFOLDED());
        if (folded) {
            wiseTopic.setShrink(folded);
        }


    }

    private
    @Nullable
    String generateFontStyle(@NotNull Node node, @Nullable Font font) {
        /*
        * MindmapFont format : fontName ; size ; color ; bold; italic;
        * eg: Verdana;10;#ffffff;bold;italic;
        *
        */

        // Font name ...
        final StringBuilder fontStyle = new StringBuilder();
        if (font != null) {
            fontStyle.append(fixFontName(font));
        }
        fontStyle.append(";");

        // Size ...
        if (font != null) {
            final BigInteger bigInteger = (font.getSIZE() == null || font.getSIZE().intValue() < 8) ? BigInteger.valueOf(8) : font.getSIZE();
            fontStyle.append(bigInteger);
        }
        fontStyle.append(";");

        // Color ...
        final String color = node.getCOLOR();
        if (color != null && !color.equals("")) {
            fontStyle.append(color);
        }
        fontStyle.append(";");

        // Bold ...
        if (font != null) {
            boolean hasBold = Boolean.parseBoolean(font.getBOLD());
            fontStyle.append(hasBold ? BOLD : "");
        }
        fontStyle.append(";");

        // Italic ...
        if (font != null) {
            boolean hasItalic = Boolean.parseBoolean(font.getITALIC());
            fontStyle.append(hasItalic ? ITALIC : "");
        }
        fontStyle.append(";");

        final String result = fontStyle.toString();
        return result.equals(EMPTY_FONT_STYLE) ? null : result;
    }

    private
    @NotNull
    String fixFontName(@NotNull Font font) {
        String result = com.wisemapping.model.Font.ARIAL.getFontName(); // Default Font
        if (com.wisemapping.model.Font.isValidFont(font.getNAME())) {
            result = font.getNAME();
        }
        return result;
    }

    private String getShapeFormFromNode(Node node) {
        String shape = node.getSTYLE();
        // In freemind a node without style is a line
        if ("bubble".equals(shape)) {
            shape = ShapeStyle.ROUNDED_RETAGLE.getStyle();
        } else {
            shape = ShapeStyle.LINE.getStyle();
        }
        return shape;
    }
}
