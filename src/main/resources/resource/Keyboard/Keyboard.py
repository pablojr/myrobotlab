# create services
python = Runtime.start("python", "Python")
keyboard = Runtime.start("keyboard", "Keyboard")
python.subscribe("keyboard", "publishMouseMoved")

# non blocking event example
keyboard.addKeyListener(python);

def onKey(key):
    print ("you pressed ", key)

def onMouseMoved(mouseEvent):
    print ("x ", mouseEvent.pos.x, " y ", mouseEvent.pos.y)

# blocking example
print "here waiting"
keypress = keyboard.readKey()
print "finally you pressed", keypress, "!"
