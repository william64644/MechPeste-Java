package com.pesterenan.controllers;

import com.pesterenan.MechPeste;
import com.pesterenan.resources.Bundle;
import com.pesterenan.utils.ControlePID;
import com.pesterenan.utils.Modulos;
import com.pesterenan.utils.Utilities;
import com.pesterenan.views.StatusJPanel;
import krpc.client.RPCException;
import krpc.client.StreamException;
import krpc.client.services.SpaceCenter.Engine;
import krpc.client.services.SpaceCenter.Fairing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LiftoffController extends FlightController implements Runnable {

private static final float PITCH_UP = 90;

private float currentPitch;
private float startCurveAlt = 100;
private float finalApoapsisAlt = 80000;
private float heading = 90;
private float roll = 90;
private boolean willDecoupleStages;
private boolean willDeployPanelsAndRadiators;
private String gravityCurveModel = Modulos.CIRCULAR.get();
private ControlePID thrControl = new ControlePID();

public LiftoffController(Map<String, String> commands) {
	super(getConexao());
	this.currentPitch = PITCH_UP;
	setFinalApoapsisAlt(Float.parseFloat(commands.get(Modulos.APOASTRO.get())));
	setHeading(Float.parseFloat(commands.get(Modulos.DIRECAO.get())));
	setRoll(Float.parseFloat(commands.get(Modulos.ROLAGEM.get())));
	setGravityCurveModel(commands.get(Modulos.INCLINACAO.get()));
	willDeployPanelsAndRadiators = Boolean.parseBoolean(commands.get(Modulos.ABRIR_PAINEIS.get()));
	willDecoupleStages = Boolean.parseBoolean(commands.get(Modulos.USAR_ESTAGIOS.get()));
}

@Override
public void run() {
	try {
		liftoff();
		gravityCurve();
		finalizeOrbit();
		circularizeOrbitOnApoapsis();
	} catch (RPCException | InterruptedException | StreamException e) {
		disengageAfterException(Bundle.getString("status_liftoff_abort"));
	}
}

private void gravityCurve() throws RPCException, StreamException, InterruptedException {
	naveAtual.getAutoPilot().targetPitchAndHeading(this.currentPitch, getHeading());
	naveAtual.getAutoPilot().setTargetRoll(this.getRoll());
	naveAtual.getAutoPilot().engage();

	throttle(1f);
	while (this.currentPitch > 1) {
		if (apoastro.get() > getFinalApoapsis()) {
			throttle(0);
			break;
		}
		double currentAltitude = Utilities.remap(startCurveAlt, getFinalApoapsis(), 1, 0.01, altitude.get());
		this.currentPitch = (float) (calculateInclinationCurve(currentAltitude) * PITCH_UP);

		naveAtual.getAutoPilot().targetPitchAndHeading(this.currentPitch, getHeading());
		throttle(thrControl.computarPID(apoastro.get(), getFinalApoapsis()));

		if (willDecoupleStages && isCurrentStageWithoutFuel()) {
			decoupleStage();
		}

		StatusJPanel.setStatus(String.format(Bundle.getString("status_liftoff_inclination") + " %.1f", currentPitch));
		Thread.sleep(250);
	}
}

private void finalizeOrbit() throws RPCException, StreamException, InterruptedException {
	StatusJPanel.setStatus(Bundle.getString("status_maintaining_until_orbit"));
	naveAtual.getAutoPilot().disengage();
	naveAtual.getControl().setSAS(true);
	naveAtual.getControl().setRCS(true);
	while (parametrosDeVoo.getDynamicPressure() > 10) {
		naveAtual.getAutoPilot().setTargetDirection(parametrosDeVoo.getPrograde());
		throttle(thrControl.computarPID(apoastro.get(), getFinalApoapsis()));
		Thread.sleep(250);
	}
	if (willDeployPanelsAndRadiators) {
		deployPanelsAndRadiators();
	}
}

private void circularizeOrbitOnApoapsis() {
	StatusJPanel.setStatus(Bundle.getString("status_planning_orbit"));
	Map<String, String> commands = new HashMap<>();
	commands.put(Modulos.MODULO.get(), Modulos.MODULO_MANOBRAS.get());
	commands.put(Modulos.FUNCAO.get(), Modulos.APOASTRO.get());
	commands.put(Modulos.AJUSTE_FINO.get(), String.valueOf(true));
	MechPeste.iniciarModulo(commands);
}

private void decoupleStage() throws InterruptedException, RPCException {
	StatusJPanel.setStatus(Bundle.getString("status_separating_stage"));
	Thread.sleep(1000);
	naveAtual.getControl().activateNextStage();
	Thread.sleep(1000);
}

private void deployPanelsAndRadiators() throws RPCException, InterruptedException {
	List<Fairing> fairings = naveAtual.getParts().getFairings();
	if (fairings.size() > 0) {
		StatusJPanel.setStatus(Bundle.getString("status_jettisoning_shields"));
		for (Fairing f : fairings) {
			// Overly complicated way of getting the event from the button in the fairing
			// to jettison the fairing, since the jettison method doesn't work.
			String eventName = f.getPart().getModules().get(0).getEvents().get(0);
			f.getPart().getModules().get(0).triggerEvent(eventName);
			Thread.sleep(10000);
		}
	}
	naveAtual.getControl().setSolarPanels(true);
	naveAtual.getControl().setRadiators(true);
}

private double calculateInclinationCurve(double currentAltitude) {
	if (gravityCurveModel.equals(Modulos.QUADRATICA.get())) {
		return Utilities.easeInQuad(currentAltitude);
	}
	if (gravityCurveModel.equals(Modulos.CUBICA.get())) {
		return Utilities.easeInCubic(currentAltitude);
	}
	if (gravityCurveModel.equals(Modulos.SINUSOIDAL.get())) {
		return Utilities.easeInSine(currentAltitude);
	}
	if (gravityCurveModel.equals(Modulos.EXPONENCIAL.get())) {
		return Utilities.easeInExpo(currentAltitude);
	}
	return Utilities.easeInCirc(currentAltitude);
}

private boolean isCurrentStageWithoutFuel() throws RPCException {
	for (Engine engine : naveAtual.getParts().getEngines()) {
		if (engine.getPart().getStage() == naveAtual.getControl().getCurrentStage() && ! engine.getHasFuel()) {
			return true;
		}
	}
	return false;
}

public float getHeading() {
	return heading;
}

public float getFinalApoapsis() {
	return finalApoapsisAlt;
}

public float getRoll() {
	return this.roll;
}

private void setGravityCurveModel(String model) {
	this.gravityCurveModel = model;
}

public void setHeading(float heading) {
	final int MIN_HEADING = 0;
	final int MAX_HEADING = 360;
	this.heading = (float) Utilities.clamp(heading, MIN_HEADING, MAX_HEADING);
}

public void setRoll(float heading) {
	final int MIN_HEADING = 0;
	final int MAX_HEADING = 360;
	this.roll = (float) Utilities.clamp(heading, MIN_HEADING, MAX_HEADING);
}

public void setFinalApoapsisAlt(float finalApoapsisAlt) {
	final int MIN_FINAL_APOAPSIS = 10000;
	final int MAX_FINAL_APOAPSIS = 2000000;
	this.finalApoapsisAlt = (float) Utilities.clamp(finalApoapsisAlt, MIN_FINAL_APOAPSIS, MAX_FINAL_APOAPSIS);
}
}
