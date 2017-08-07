# BEWARE ! Makefiles require the use of hard tabs

OUT_JS_BUNDLE := mindmap-viewer-bundle.js

.PHONY: all clean

all: $(OUT_JS_BUNDLE)
	@:

$(OUT_JS_BUNDLE): */files-order.txt libraries/*.js mindplot/*.js main.js
	cat $$(cat libraries/files-order.txt mindplot/files-order.txt) main.js > $@

clean:
	@$(RM) $(OUT_JS_BUNDLE)
