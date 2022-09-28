// This file is part of Dotterel which is released under GPL-2.0-or-later.
// See file <LICENSE.txt> or go to <http://www.gnu.org/licenses/> for full license details.

package nimble.dotterel.machines

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

import com.eclipsesource.json.JsonObject

import nimble.dotterel.*
import nimble.dotterel.translation.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.abs

enum class Button(val sdlName: String)
{
	A("a"),
	B("b"),
	X("x"),
	Y("y"),
	Back("back"),
	Guide("guide"),
	Start("start"),
	LeftStick("leftstick"),
	RightStick("rightstick"),
	LeftTrigger("lefttrigger"),
	LeftShoulder("leftshoulder"),
	RightTrigger("righttrigger"),
	RightShoulder("rightshoulder"),
	DpUp("dpup"),
	DpDown("dpdown"),
	DpLeft("dpleft"),
	DpRight("dpright"),
	Misc1("misc1"),
	Paddle1("paddle1"),
	Paddle2("paddle2"),
	Paddle3("paddle3"),
	Paddle4("paddle4"),
	Touchpad("touchpad"),
}

enum class Stick(val configName: String)
{
	Left("left"),
	Right("right"),
}

sealed class Directive
{
	class Stick(val name: String, val segmentCount: Int, val offset: Float, val directions: List<String>) : Directive()
	class ButtonMapping(val buttons: List<String>, val result: String) : Directive()
	class MotionMapping(val stick: String, val directions: List<String>, val result: String) : Directive()
}

class Mapping
{
	val sticks = hashMapOf<String, Directive.Stick>()
	val buttonMappings = mutableListOf<Directive.ButtonMapping>()
	val motionMappings = mutableListOf<Directive.MotionMapping>()

	constructor(text: String)
	{
		text.lines().map { line ->
			var result = parseLine(line)
			Log.v("Dotterel", result.toString())
			if(result is Directive.Stick)
				this.sticks[result.name] = result
			else if(result is Directive.ButtonMapping)
				this.buttonMappings.add(result)
			else if(result is Directive.MotionMapping)
				this.motionMappings.add(result)
			else
				Unit
		}
	}

	val stickDeclRegex = Regex("""(\w+) stick has (\d) segments offset by ([0-9-.]+) degrees \(([a-z,]+)\)""")
	val buttonMappingRegex = Regex("""([a-z0-9,]+) -> ([A-Z-*#]+)""")
	val motionMappingRegex = Regex("""(left|right)\(([a-z,]+)\) -> ([A-Z-*#]+)""")

	private fun parseLine(line: String): Directive?
	{
		val stickDeclMatch = stickDeclRegex.matchEntire(line)
		if(stickDeclMatch != null)
		{
			return Directive.Stick(
				stickDeclMatch.groupValues[1],
				stickDeclMatch.groupValues[2].toInt(),
				stickDeclMatch.groupValues[3].toFloat(),
				stickDeclMatch.groupValues[4].split(",")
			)
		}
		val buttonMappingMatch = buttonMappingRegex.matchEntire(line)
		if(buttonMappingMatch != null)
		{
			return Directive.ButtonMapping(
				buttonMappingMatch.groupValues[1].split(","),
				buttonMappingMatch.groupValues[2]
			)
		}
		val motionMappingMatch = motionMappingRegex.matchEntire(line)
		if(motionMappingMatch != null)
		{
			return Directive.MotionMapping(
				motionMappingMatch.groupValues[1],
				motionMappingMatch.groupValues[2].split(","),
				motionMappingMatch.groupValues[3]
			)
		}
		return null
	}
}

class ControllerStenoMachine(private val app: Dotterel) :
	StenoMachine, Dotterel.KeyListener
{
	private var keyLayout: KeyLayout = KeyLayout("")
	private var mapping = Mapping("")
	private var stickDeadZone: Float = 0.5f
	private var triggerDeadZone: Float = 0.5f
	private var axisStates = hashMapOf<Int, Float>()
	private var buttonsDown = mutableSetOf<String>()
	private var strokedInputs = mutableListOf<Stroke>()
	private var buttonsDownDuringStroke = mutableSetOf<String>()
	private var stickSequences = hashMapOf<String, MutableList<String>>()
	override var strokeListener: StenoMachine.Listener? = null

	class Factory : StenoMachine.Factory
	{
		override var tracker: StenoMachineTracker? = null
			set(v)
			{
				field = v
				this.tracker?.addMachine(Pair("Controller", ""))
			}

		override fun makeStenoMachine(app: Dotterel, id: String) = ControllerStenoMachine(app)
	}

	init
	{
		this.app.addKeyListener(this)
	}

	override fun setConfig(
		keyLayout: KeyLayout,
		config: JsonObject,
		systemConfig: JsonObject
	)
	{
		try
		{
			this.keyLayout = keyLayout
			this.mapping = Mapping(config["mapping"].asString())
			this.stickDeadZone = (config.get("stickDeadZone")?.asFloat() ?: 50.0f) / 100.0f
			this.triggerDeadZone = (config.get("triggerDeadZone")?.asFloat() ?: 50.0f) / 100.0f
		}
		catch(e: java.lang.NullPointerException)
		{
			throw IllegalArgumentException(e)
		}
		catch(e: java.lang.UnsupportedOperationException)
		{
			throw IllegalArgumentException(e)
		}
	}

	private fun handleStick(stick: Stick, x: Float, y: Float)
	{
		if(hypot(x, y) > stickDeadZone * sqrt(2.0f))
		{
			val stickDef = this.mapping.sticks.get(stick.configName)
			if(stickDef == null)
				return
			val offset = stickDef.offset.toDouble() / 360.0 * PI * 2.0
			var angle = atan2(y, x) - offset
			while(angle < 0.0)
				angle += 2.0f * PI.toFloat()
			while(angle > 2.0 * PI)
				angle -= 2.0f * PI.toFloat()
			val segments = stickDef.segmentCount.toDouble()
			val segment = floor(angle / (PI * 2.0) * segments).toInt()
			if(!stickSequences.containsKey(stick.configName))
				stickSequences[stick.configName] = mutableListOf<String>()
			val stickSequence = stickSequences.get(stick.configName)
			val direction = stickDef.directions[segment]
			if(stickSequence!!.lastOrNull() != direction)
				stickSequence.add(direction)
		}
		this.maybeCompleteMotionInput()
		this.maybeCompleteStroke()
	}

	private fun handleDpad(x: Float, y: Float)
	{
		val buttons = listOf(
			Pair(Button.DpLeft, x < 0),
			Pair(Button.DpRight, x > 0),
			Pair(Button.DpUp, y < 0),
			Pair(Button.DpDown, y > 0)
		)
		buttons.forEach { (button, state) ->
			this.handleButton(button, state)
		}
	}

	private fun handleButton(button: Button, state: Boolean)
	{
		if(state)
		{
			this.buttonsDown.add(button.sdlName)
			this.buttonsDownDuringStroke.add(button.sdlName)
		}
		else
		{
			this.buttonsDown.remove(button.sdlName)
		}
		this.maybeCompleteStroke()
	}

	private fun maybeCompleteMotionInput()
	{
		val sticks = listOf(
			Triple(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, Stick.Left),
			Triple(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, Stick.Right)
		)
		sticks.forEach { (xAxis, yAxis, stick) ->
			if(abs(this.axisStates.get(xAxis) ?: 0.0f) < this.stickDeadZone
				&& abs(this.axisStates.get(yAxis) ?: 0.0f) < this.stickDeadZone)
			{
				val sequence = stickSequences[stick.configName]
				if(sequence != null)
				{
					Log.v("dotterel", "maybeCompleteMotionInput $stick ${sequence}")
					val found = this.mapping.motionMappings.find { mapping ->
						mapping.stick == stick.configName && mapping.directions == sequence
					}
					if(found != null)
					{
						this.strokedInputs.add(this.keyLayout.parse(found.result))
					}
					else
					{
						this.buttonsDownDuringStroke.addAll(sequence.map { "${stick.configName}$it" })
					}
					sequence.clear()

				}
			}
		}
	}

	private fun maybeCompleteStroke()
	{
		var stroke = this.strokedInputs.fold(Stroke(this.keyLayout, 0)) { acc, it -> acc + it }
		val buttons = this.buttonsDownDuringStroke.toMutableSet<String>()
		this.mapping.buttonMappings.forEach { mapping ->
			if(mapping.buttons.all { buttons.contains(it) })
			{
				mapping.buttons.forEach { buttons.remove(it) }
				stroke += this.keyLayout.parse(mapping.result)
			}
		}
		if(this.canCompleteStroke())
		{
			this.buttonsDownDuringStroke.clear()
			this.strokedInputs.clear()
			this.strokeListener?.applyStroke(stroke)
		}
		else
		{
			this.strokeListener?.changeStroke(stroke)
		}
	}

	private fun canCompleteStroke(): Boolean
	{
		if(!this.buttonsDown.isEmpty())
			return false
		val sticks = listOf(
			Triple(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, Stick.Left),
			Triple(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, Stick.Right)
		)
		sticks.forEach { (xAxis, yAxis, _) ->
			if(abs(this.axisStates.get(xAxis) ?: 0.0f) > this.stickDeadZone
				|| abs(this.axisStates.get(yAxis) ?: 0.0f) > this.stickDeadZone)
			{
				return false
			}
		}
		return true
	}

	override fun genericMotionEvent(e: MotionEvent): Boolean
	{
		if(e.source != InputDevice.SOURCE_JOYSTICK)
			return false

		val sticks = listOf(
			Triple(Stick.Left, MotionEvent.AXIS_X, MotionEvent.AXIS_Y),
			Triple(Stick.Right, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
		)
		if(e.actionMasked == MotionEvent.ACTION_MOVE)
		{
			sticks.forEach { (stick, xAxis, yAxis) ->
				(0 until e.historySize).forEach { pos ->
					val x = e.getHistoricalAxisValue(xAxis, pos)
					val y = e.getHistoricalAxisValue(yAxis, pos)
					this.handleStick(stick, x, y)
				}
			}
		}
		sticks.forEach { (stick, xAxis, yAxis) ->
			val x = e.getAxisValue(xAxis)
			val y = e.getAxisValue(yAxis)
			this.axisStates[xAxis] = x
			this.axisStates[yAxis] = y
			this.handleStick(stick, x, y)
		}

		val triggers = listOf(
			Pair(Button.LeftTrigger, MotionEvent.AXIS_BRAKE),
			Pair(Button.RightTrigger, MotionEvent.AXIS_GAS)
		)
		triggers.forEach { (button, axis) ->
			this.handleButton(button, e.getAxisValue(axis) > triggerDeadZone)
		}

		this.handleDpad(
			e.getAxisValue(MotionEvent.AXIS_HAT_X),
			e.getAxisValue(MotionEvent.AXIS_HAT_Y)
		)

		return true
	}

	override fun keyDown(e: KeyEvent): Boolean
	{
		if(!(e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD))
			return false

		val button = keyCodeToButton(e.keyCode)
		if(button != null)
			this.handleButton(button, true)

		return true
	}

	override fun keyUp(e: KeyEvent): Boolean
	{
		if(!(e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD))
			return false

		val button = keyCodeToButton(e.keyCode)
		if(button != null)
			this.handleButton(button, false)

		return true
	}

	private fun keyCodeToButton(code: Int): Button? = when(code)
	{
		KeyEvent.KEYCODE_BUTTON_A -> Button.A
		KeyEvent.KEYCODE_BUTTON_B -> Button.B
		KeyEvent.KEYCODE_BUTTON_X -> Button.X
		KeyEvent.KEYCODE_BUTTON_Y -> Button.Y
		KeyEvent.KEYCODE_BUTTON_SELECT -> Button.Back
		KeyEvent.KEYCODE_BUTTON_MODE -> Button.Guide
		KeyEvent.KEYCODE_BUTTON_START -> Button.Start
		KeyEvent.KEYCODE_BUTTON_THUMBL -> Button.LeftStick
		KeyEvent.KEYCODE_BUTTON_THUMBR -> Button.RightStick
		KeyEvent.KEYCODE_BUTTON_L1 -> Button.LeftShoulder
		KeyEvent.KEYCODE_BUTTON_R1 -> Button.RightShoulder
		else -> null
	}

	override fun close()
	{
		this.app.removeKeyListener(this)
	}
}
